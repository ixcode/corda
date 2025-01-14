package net.corda.node.services.statemachine

import co.paralleluniverse.fibers.Fiber
import co.paralleluniverse.fibers.FiberExecutorScheduler
import co.paralleluniverse.fibers.Suspendable
import co.paralleluniverse.fibers.instrument.SuspendableHelper
import co.paralleluniverse.strands.channels.Channels
import com.codahale.metrics.Gauge
import net.corda.core.concurrent.CordaFuture
import net.corda.core.context.InvocationContext
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowInfo
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.Party
import net.corda.core.internal.*
import net.corda.core.internal.concurrent.OpenFuture
import net.corda.core.internal.concurrent.map
import net.corda.core.internal.concurrent.openFuture
import net.corda.core.messaging.DataFeed
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.internal.CheckpointSerializationContext
import net.corda.core.serialization.internal.CheckpointSerializationDefaults
import net.corda.core.serialization.internal.checkpointDeserialize
import net.corda.core.serialization.internal.checkpointSerialize
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.Try
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.node.internal.InitiatedFlowFactory
import net.corda.node.services.api.CheckpointStorage
import net.corda.node.services.api.ServiceHubInternal
import net.corda.node.services.config.shouldCheckCheckpoints
import net.corda.node.services.messaging.DeduplicationHandler
import net.corda.node.services.statemachine.FlowStateMachineImpl.Companion.createSubFlowVersion
import net.corda.node.services.statemachine.interceptors.*
import net.corda.node.services.statemachine.transitions.StateMachine
import net.corda.node.utilities.AffinityExecutor
import net.corda.node.utilities.errorAndTerminate
import net.corda.node.utilities.injectOldProgressTracker
import net.corda.node.utilities.isEnabledTimedFlow
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.nodeapi.internal.persistence.wrapWithDatabaseTransaction
import net.corda.serialization.internal.CheckpointSerializeAsTokenContextImpl
import net.corda.serialization.internal.withTokenContext
import org.apache.activemq.artemis.utils.ReusableLatch
import rx.Observable
import rx.subjects.PublishSubject
import java.lang.Integer.min
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.*
import javax.annotation.concurrent.ThreadSafe
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.streams.toList

/**
 * The StateMachineManagerImpl will always invoke the flow fibers on the given [AffinityExecutor], regardless of which
 * thread actually starts them via [deliverExternalEvent].
 */
@ThreadSafe
class SingleThreadedStateMachineManager(
        val serviceHub: ServiceHubInternal,
        private val checkpointStorage: CheckpointStorage,
        val executor: ExecutorService,
        val database: CordaPersistence,
        private val secureRandom: SecureRandom,
        private val unfinishedFibers: ReusableLatch = ReusableLatch(),
        private val classloader: ClassLoader = SingleThreadedStateMachineManager::class.java.classLoader
) : StateMachineManager, StateMachineManagerInternal {
    companion object {
        private val logger = contextLogger()
    }

    private class Flow(val fiber: FlowStateMachineImpl<*>, val resultFuture: OpenFuture<Any?>)

    private data class ScheduledTimeout(
            /** Will fire a [FlowTimeoutException] indicating to the flow hospital to restart the flow. */
            val scheduledFuture: ScheduledFuture<*>,
            /** Specifies the number of times this flow has been retried. */
            val retryCount: Int = 0
    )

    // A list of all the state machines being managed by this class. We expose snapshots of it via the stateMachines
    // property.
    private class InnerState {
        val changesPublisher = PublishSubject.create<StateMachineManager.Change>()!!
        /** True if we're shutting down, so don't resume anything. */
        var stopping = false
        val flows = HashMap<StateMachineRunId, Flow>()
        val startedFutures = HashMap<StateMachineRunId, OpenFuture<Unit>>()
        /** Flows scheduled to be retried if not finished within the specified timeout period. */
        val timedFlows = HashMap<StateMachineRunId, ScheduledTimeout>()
    }

    private val mutex = ThreadBox(InnerState())
    private val scheduler = FiberExecutorScheduler("Same thread scheduler", executor)
    private val timeoutScheduler = Executors.newScheduledThreadPool(1)
    // How many Fibers are running and not suspended.  If zero and stopping is true, then we are halted.
    private val liveFibers = ReusableLatch()
    // Monitoring support.
    private val metrics = serviceHub.monitoringService.metrics
    private val sessionToFlow = ConcurrentHashMap<SessionId, StateMachineRunId>()
    private val flowMessaging: FlowMessaging = FlowMessagingImpl(serviceHub)
    private val fiberDeserializationChecker = if (serviceHub.configuration.shouldCheckCheckpoints()) FiberDeserializationChecker() else null
    private val ourSenderUUID = serviceHub.networkService.ourSenderUUID

    private var checkpointSerializationContext: CheckpointSerializationContext? = null
    private var actionExecutor: ActionExecutor? = null

    override val flowHospital: StaffedFlowHospital = StaffedFlowHospital(flowMessaging, ourSenderUUID)
    private val transitionExecutor = makeTransitionExecutor()

    override val allStateMachines: List<FlowLogic<*>>
        get() = mutex.locked { flows.values.map { it.fiber.logic } }

    private val totalStartedFlows = metrics.counter("Flows.Started")
    private val totalFinishedFlows = metrics.counter("Flows.Finished")

    /**
     * An observable that emits triples of the changing flow, the type of change, and a process-specific ID number
     * which may change across restarts.
     *
     * We use assignment here so that multiple subscribers share the same wrapped Observable.
     */
    override val changes: Observable<StateMachineManager.Change> = mutex.content.changesPublisher

    override fun start(tokenizableServices: List<Any>) {
        checkQuasarJavaAgentPresence()
        val checkpointSerializationContext = CheckpointSerializationDefaults.CHECKPOINT_CONTEXT.withTokenContext(
                CheckpointSerializeAsTokenContextImpl(
                        tokenizableServices,
                        CheckpointSerializationDefaults.CHECKPOINT_SERIALIZER,
                        CheckpointSerializationDefaults.CHECKPOINT_CONTEXT,
                        serviceHub
                )
        )
        this.checkpointSerializationContext = checkpointSerializationContext
        this.actionExecutor = makeActionExecutor(checkpointSerializationContext)
        fiberDeserializationChecker?.start(checkpointSerializationContext)
        val fibers = restoreFlowsFromCheckpoints()
        metrics.register("Flows.InFlight", Gauge<Int> { mutex.content.flows.size })
        Fiber.setDefaultUncaughtExceptionHandler { fiber, throwable ->
            if (throwable is VirtualMachineError) {
                errorAndTerminate("Caught unrecoverable error from flow. Forcibly terminating the JVM, this might leave resources open, and most likely will.", throwable)
            } else {
                (fiber as FlowStateMachineImpl<*>).logger.warn("Caught exception from flow", throwable)
            }
        }
        serviceHub.networkMapCache.nodeReady.then {
            logger.info("Node ready, info: ${serviceHub.myInfo}")
            resumeRestoredFlows(fibers)
            flowMessaging.start { _, deduplicationHandler ->
                executor.execute {
                    deliverExternalEvent(deduplicationHandler.externalCause)
                }
            }
        }
    }

    override fun snapshot(): Set<FlowStateMachineImpl<*>> = mutex.content.flows.values.map { it.fiber }.toSet()

    override fun <A : FlowLogic<*>> findStateMachines(flowClass: Class<A>): List<Pair<A, CordaFuture<*>>> {
        return mutex.locked {
            flows.values.mapNotNull {
                flowClass.castIfPossible(it.fiber.logic)?.let { it to it.stateMachine.resultFuture }
            }
        }
    }

    /**
     * Start the shutdown process, bringing the [SingleThreadedStateMachineManager] to a controlled stop.  When this method returns,
     * all Fibers have been suspended and checkpointed, or have completed.
     *
     * @param allowedUnsuspendedFiberCount Optional parameter is used in some tests.
     */
    override fun stop(allowedUnsuspendedFiberCount: Int) {
        require(allowedUnsuspendedFiberCount >= 0){"allowedUnsuspendedFiberCount must be greater than or equal to zero"}
        mutex.locked {
            if (stopping) throw IllegalStateException("Already stopping!")
            stopping = true
            for ((_, flow) in flows) {
                flow.fiber.scheduleEvent(Event.SoftShutdown)
            }
        }
        // Account for any expected Fibers in a test scenario.
        liveFibers.countDown(allowedUnsuspendedFiberCount)
        liveFibers.await()
        fiberDeserializationChecker?.let {
            val foundUnrestorableFibers = it.stop()
            check(!foundUnrestorableFibers) { "Unrestorable checkpoints were created, please check the logs for details." }
        }
    }

    /**
     * Atomic get snapshot + subscribe. This is needed so we don't miss updates between subscriptions to [changes] and
     * calls to [allStateMachines]
     */
    override fun track(): DataFeed<List<FlowLogic<*>>, StateMachineManager.Change> {
        return mutex.locked {
            database.transaction {
                DataFeed(flows.values.map { it.fiber.logic }, changesPublisher.bufferUntilSubscribed().wrapWithDatabaseTransaction(database))
            }
        }
    }

    private fun <A> startFlow(
            flowLogic: FlowLogic<A>,
            context: InvocationContext,
            ourIdentity: Party?,
            deduplicationHandler: DeduplicationHandler?
    ): CordaFuture<FlowStateMachine<A>> {
        return startFlowInternal(
                invocationContext = context,
                flowLogic = flowLogic,
                flowStart = FlowStart.Explicit,
                ourIdentity = ourIdentity ?: ourFirstIdentity,
                deduplicationHandler = deduplicationHandler,
                isStartIdempotent = false
        )
    }

    override fun killFlow(id: StateMachineRunId): Boolean {
        return mutex.locked {
            cancelTimeoutIfScheduled(id)
            val flow = flows.remove(id)
            if (flow != null) {
                logger.debug("Killing flow known to physical node.")
                decrementLiveFibers()
                totalFinishedFlows.inc()
                try {
                    flow.fiber.interrupt()
                    true
                } finally {
                    database.transaction {
                        checkpointStorage.removeCheckpoint(id)
                    }
                    transitionExecutor.forceRemoveFlow(id)
                    unfinishedFibers.countDown()
                }
            } else {
                // It may be that the id refers to a checkpoint that couldn't be deserialised into a flow, so we delete it if it exists.
                database.transaction {
                    checkpointStorage.removeCheckpoint(id)
                }
            }
        }
    }

    override fun addSessionBinding(flowId: StateMachineRunId, sessionId: SessionId) {
        val previousFlowId = sessionToFlow.put(sessionId, flowId)
        if (previousFlowId != null) {
            if (previousFlowId == flowId) {
                logger.warn("Session binding from $sessionId to $flowId re-added")
            } else {
                throw IllegalStateException(
                        "Attempted to add session binding from session $sessionId to flow $flowId, " +
                                "however there was already a binding to $previousFlowId"
                )
            }
        }
    }

    override fun removeSessionBindings(sessionIds: Set<SessionId>) {
        val reRemovedSessionIds = HashSet<SessionId>()
        for (sessionId in sessionIds) {
            val flowId = sessionToFlow.remove(sessionId)
            if (flowId == null) {
                reRemovedSessionIds.add(sessionId)
            }
        }
        if (reRemovedSessionIds.isNotEmpty()) {
            logger.warn("Session binding from $reRemovedSessionIds re-removed")
        }
    }

    override fun removeFlow(flowId: StateMachineRunId, removalReason: FlowRemovalReason, lastState: StateMachineState) {
        mutex.locked {
            cancelTimeoutIfScheduled(flowId)
            val flow = flows.remove(flowId)
            if (flow != null) {
                decrementLiveFibers()
                totalFinishedFlows.inc()
                return when (removalReason) {
                    is FlowRemovalReason.OrderlyFinish -> removeFlowOrderly(flow, removalReason, lastState)
                    is FlowRemovalReason.ErrorFinish -> removeFlowError(flow, removalReason, lastState)
                    FlowRemovalReason.SoftShutdown -> flow.fiber.scheduleEvent(Event.SoftShutdown)
                }
            } else {
                logger.warn("Flow $flowId re-finished")
            }
        }
    }

    override fun signalFlowHasStarted(flowId: StateMachineRunId) {
        mutex.locked {
            startedFutures.remove(flowId)?.set(Unit)
            flows[flowId]?.let { flow ->
                changesPublisher.onNext(StateMachineManager.Change.Add(flow.fiber.logic))
            }
        }
    }

    private fun checkQuasarJavaAgentPresence() {
        check(SuspendableHelper.isJavaAgentActive()) {
            """Missing the '-javaagent' JVM argument. Make sure you run the tests with the Quasar java agent attached to your JVM.
               #See https://docs.corda.net/head/testing.html#running-tests-in-intellij - 'Fiber classes not instrumented' for more details.""".trimMargin("#")
        }
    }

    private fun decrementLiveFibers() {
        liveFibers.countDown()
    }

    private fun incrementLiveFibers() {
        liveFibers.countUp()
    }

    private fun restoreFlowsFromCheckpoints(): List<Flow> {
        return checkpointStorage.getAllCheckpoints().use {
            it.mapNotNull { (id, serializedCheckpoint) ->
                // If a flow is added before start() then don't attempt to restore it
                mutex.locked { if (id in flows) return@mapNotNull null }
                createFlowFromCheckpoint(
                        id = id,
                        serializedCheckpoint = serializedCheckpoint,
                        initialDeduplicationHandler = null,
                        isAnyCheckpointPersisted = true,
                        isStartIdempotent = false
                )
            }.toList()
        }
    }

    private fun resumeRestoredFlows(flows: List<Flow>) {
        for (flow in flows) {
            addAndStartFlow(flow.fiber.id, flow)
        }
    }

    override fun retryFlowFromSafePoint(currentState: StateMachineState) {
        // Get set of external events
        val flowId = currentState.flowLogic.runId
        val oldFlowLeftOver = mutex.locked { flows[flowId] }?.fiber?.transientValues?.value?.eventQueue
        if (oldFlowLeftOver == null) {
            logger.error("Unable to find flow for flow $flowId. Something is very wrong. The flow will not retry.")
            return
        }
        val flow = if (currentState.isAnyCheckpointPersisted) {
            // We intentionally grab the checkpoint from storage rather than relying on the one referenced by currentState. This is so that
            // we mirror exactly what happens when restarting the node.
            val serializedCheckpoint = checkpointStorage.getCheckpoint(flowId)
            if (serializedCheckpoint == null) {
                logger.error("Unable to find database checkpoint for flow $flowId. Something is very wrong. The flow will not retry.")
                return
            }
            // Resurrect flow
            createFlowFromCheckpoint(
                    id = flowId,
                    serializedCheckpoint = serializedCheckpoint,
                    initialDeduplicationHandler = null,
                    isAnyCheckpointPersisted = true,
                    isStartIdempotent = false
            ) ?: return
        } else {
            // Just flow initiation message
            null
        }
        mutex.locked {
            if (stopping) {
                return
            }
            // Remove any sessions the old flow has.
            for (sessionId in getFlowSessionIds(currentState.checkpoint)) {
                sessionToFlow.remove(sessionId)
            }
            if (flow != null) {
                injectOldProgressTracker(currentState.flowLogic.progressTracker, flow.fiber.logic)
                addAndStartFlow(flowId, flow)
            }
            // Deliver all the external events from the old flow instance.
            val unprocessedExternalEvents = mutableListOf<ExternalEvent>()
            do {
                val event = oldFlowLeftOver.tryReceive()
                if (event is Event.GeneratedByExternalEvent) {
                    unprocessedExternalEvents += event.deduplicationHandler.externalCause
                }
            } while (event != null)
            val externalEvents = currentState.pendingDeduplicationHandlers.map { it.externalCause } + unprocessedExternalEvents
            for (externalEvent in externalEvents) {
                deliverExternalEvent(externalEvent)
            }
        }
    }

    override fun deliverExternalEvent(event: ExternalEvent) {
        mutex.locked {
            if (!stopping) {
                when (event) {
                    is ExternalEvent.ExternalMessageEvent -> onSessionMessage(event)
                    is ExternalEvent.ExternalStartFlowEvent<*> -> onExternalStartFlow(event)
                }
            }
        }
    }

    private fun <T> onExternalStartFlow(event: ExternalEvent.ExternalStartFlowEvent<T>) {
        val future = startFlow(event.flowLogic, event.context, ourIdentity = null, deduplicationHandler = event.deduplicationHandler)
        event.wireUpFuture(future)
    }

    private fun onSessionMessage(event: ExternalEvent.ExternalMessageEvent) {
        val peer = event.receivedMessage.peer
        val sessionMessage = try {
            event.receivedMessage.data.deserialize<SessionMessage>()
        } catch (ex: Exception) {
            logger.error("Unable to deserialize SessionMessage data from $peer", ex)
            event.deduplicationHandler.afterDatabaseTransaction()
            return
        }
        val sender = serviceHub.networkMapCache.getPeerByLegalName(peer)
        if (sender != null) {
            when (sessionMessage) {
                is ExistingSessionMessage -> onExistingSessionMessage(sessionMessage, event.deduplicationHandler, sender)
                is InitialSessionMessage -> onSessionInit(sessionMessage, sender, event)
            }
        } else {
            // TODO Send the event to the flow hospital to be retried on network map update
            // TODO Test that restarting the node attempts to retry
            logger.error("Unknown peer $peer in $sessionMessage")
        }
    }

    private fun onExistingSessionMessage(sessionMessage: ExistingSessionMessage, deduplicationHandler: DeduplicationHandler, sender: Party) {
        try {
            val recipientId = sessionMessage.recipientSessionId
            val flowId = sessionToFlow[recipientId]
            if (flowId == null) {
                deduplicationHandler.afterDatabaseTransaction()
                if (sessionMessage.payload === EndSessionMessage) {
                    logger.debug {
                        "Got ${EndSessionMessage::class.java.simpleName} for " +
                                "unknown session $recipientId, discarding..."
                    }
                } else {
                    // It happens when flows restart and the old sessions messages still arrive from a peer.
                    logger.info("Cannot find flow corresponding to session ID - $recipientId.")
                }
            } else {
                mutex.locked { flows[flowId] }?.run {
                    fiber.scheduleEvent(Event.DeliverSessionMessage(sessionMessage, deduplicationHandler, sender))
                } ?: logger.info("Cannot find fiber corresponding to flow ID $flowId")
            }
        } catch (exception: Exception) {
            logger.error("Exception while routing $sessionMessage", exception)
            throw exception
        }
    }

    private fun onSessionInit(sessionMessage: InitialSessionMessage, sender: Party, event: ExternalEvent.ExternalMessageEvent) {
        try {
            val initiatedFlowFactory = getInitiatedFlowFactory(sessionMessage)
            val initiatedSessionId = SessionId.createRandom(secureRandom)
            val senderSession = FlowSessionImpl(sender, initiatedSessionId)
            val flowLogic = initiatedFlowFactory.createFlow(senderSession)
            val initiatedFlowInfo = when (initiatedFlowFactory) {
                is InitiatedFlowFactory.Core -> FlowInfo(serviceHub.myInfo.platformVersion, "corda")
                is InitiatedFlowFactory.CorDapp -> FlowInfo(initiatedFlowFactory.flowVersion, initiatedFlowFactory.appName)
            }
            val senderCoreFlowVersion = when (initiatedFlowFactory) {
                is InitiatedFlowFactory.Core -> event.receivedMessage.platformVersion
                is InitiatedFlowFactory.CorDapp -> null
            }
            startInitiatedFlow(flowLogic, event.deduplicationHandler, senderSession, initiatedSessionId, sessionMessage, senderCoreFlowVersion, initiatedFlowInfo)
        } catch (t: Throwable) {
            logger.warn("Unable to initiate flow from $sender (appName=${sessionMessage.appName} " +
                    "flowVersion=${sessionMessage.flowVersion}), sending to the flow hospital", t)
            flowHospital.sessionInitErrored(sessionMessage, sender, event, t)
        }
    }

    // TODO this is a temporary hack until we figure out multiple identities
    private val ourFirstIdentity: Party get() = serviceHub.myInfo.legalIdentities[0]

    private fun getInitiatedFlowFactory(message: InitialSessionMessage): InitiatedFlowFactory<*> {
        val initiatorClass = try {
            Class.forName(message.initiatorFlowClassName, true, classloader)
        } catch (e: ClassNotFoundException) {
            throw SessionRejectException.UnknownClass(message.initiatorFlowClassName)
        }

        val initiatorFlowClass = try {
            initiatorClass.asSubclass(FlowLogic::class.java)
        } catch (e: ClassCastException) {
            throw SessionRejectException.NotAFlow(initiatorClass)
        }

        return serviceHub.getFlowFactory(initiatorFlowClass) ?: throw SessionRejectException.NotRegistered(initiatorFlowClass)
    }

    private fun <A> startInitiatedFlow(
            flowLogic: FlowLogic<A>,
            initiatingMessageDeduplicationHandler: DeduplicationHandler,
            peerSession: FlowSessionImpl,
            initiatedSessionId: SessionId,
            initiatingMessage: InitialSessionMessage,
            senderCoreFlowVersion: Int?,
            initiatedFlowInfo: FlowInfo
    ) {
        val flowStart = FlowStart.Initiated(peerSession, initiatedSessionId, initiatingMessage, senderCoreFlowVersion, initiatedFlowInfo)
        val ourIdentity = ourFirstIdentity
        startFlowInternal(
                InvocationContext.peer(peerSession.counterparty.name), flowLogic, flowStart, ourIdentity,
                initiatingMessageDeduplicationHandler,
                isStartIdempotent = false
        )
    }

    private fun <A> startFlowInternal(
            invocationContext: InvocationContext,
            flowLogic: FlowLogic<A>,
            flowStart: FlowStart,
            ourIdentity: Party,
            deduplicationHandler: DeduplicationHandler?,
            isStartIdempotent: Boolean
    ): CordaFuture<FlowStateMachine<A>> {
        val flowId = StateMachineRunId.createRandom()

        // Before we construct the state machine state by freezing the FlowLogic we need to make sure that lazy properties
        // have access to the fiber (and thereby the service hub)
        val flowStateMachineImpl = FlowStateMachineImpl(flowId, flowLogic, scheduler)
        val resultFuture = openFuture<Any?>()
        flowStateMachineImpl.transientValues = TransientReference(createTransientValues(flowId, resultFuture))
        flowLogic.stateMachine = flowStateMachineImpl
        val frozenFlowLogic = (flowLogic as FlowLogic<*>).checkpointSerialize(context = checkpointSerializationContext!!)

        val flowCorDappVersion = createSubFlowVersion(serviceHub.cordappProvider.getCordappForFlow(flowLogic), serviceHub.myInfo.platformVersion)

        val initialCheckpoint = Checkpoint.create(
                invocationContext,
                flowStart,
                flowLogic.javaClass,
                frozenFlowLogic,
                ourIdentity,
                flowCorDappVersion,
                flowLogic.isEnabledTimedFlow()
        ).getOrThrow()
        val startedFuture = openFuture<Unit>()
        val initialState = StateMachineState(
                checkpoint = initialCheckpoint,
                pendingDeduplicationHandlers = deduplicationHandler?.let { listOf(it) } ?: emptyList(),
                isFlowResumed = false,
                isTransactionTracked = false,
                isAnyCheckpointPersisted = false,
                isStartIdempotent = isStartIdempotent,
                isRemoved = false,
                flowLogic = flowLogic,
                senderUUID = ourSenderUUID
        )
        flowStateMachineImpl.transientState = TransientReference(initialState)
        mutex.locked {
            startedFutures[flowId] = startedFuture
        }
        totalStartedFlows.inc()
        addAndStartFlow(flowId, Flow(flowStateMachineImpl, resultFuture))
        return startedFuture.map { flowStateMachineImpl as FlowStateMachine<A> }
    }

    override fun scheduleFlowTimeout(flowId: StateMachineRunId) {
        mutex.locked { scheduleTimeout(flowId) }
    }

    override fun cancelFlowTimeout(flowId: StateMachineRunId) {
        mutex.locked { cancelTimeoutIfScheduled(flowId) }
    }

    /**
     * Schedules the flow [flowId] to be retried if it does not finish within the timeout period
     * specified in the config.
     *
     * Assumes lock is taken on the [InnerState].
     */
    private fun InnerState.scheduleTimeout(flowId: StateMachineRunId) {
        val flow = flows[flowId]
        if (flow != null) {
            val scheduledTimeout = timedFlows[flowId]
            val retryCount = if (scheduledTimeout != null) {
                val timeoutFuture = scheduledTimeout.scheduledFuture
                if (!timeoutFuture.isDone) scheduledTimeout.scheduledFuture.cancel(true)
                scheduledTimeout.retryCount
            } else 0
            val scheduledFuture = scheduleTimeoutException(flow, calculateDefaultTimeoutSeconds(retryCount))
            timedFlows[flowId] = ScheduledTimeout(scheduledFuture, retryCount + 1)
        } else {
            logger.warn("Unable to schedule timeout for flow $flowId – flow not found.")
        }
    }

    private fun resetCustomTimeout(flowId: StateMachineRunId, timeoutSeconds: Long) {
        if (timeoutSeconds < serviceHub.configuration.flowTimeout.timeout.seconds) {
            logger.debug { "Ignoring request to set time-out on timed flow $flowId to $timeoutSeconds seconds which is shorter than default of ${serviceHub.configuration.flowTimeout.timeout.seconds} seconds." }
            return
        }
        logger.debug { "Processing request to set time-out on timed flow $flowId to $timeoutSeconds seconds." }
        mutex.locked {
            resetCustomTimeout(flowId, timeoutSeconds)
        }
    }

    private fun InnerState.resetCustomTimeout(flowId: StateMachineRunId, timeoutSeconds: Long) {
        val flow = flows[flowId]
        if (flow != null) {
            val scheduledTimeout = timedFlows[flowId]
            val retryCount = if (scheduledTimeout != null) {
                val timeoutFuture = scheduledTimeout.scheduledFuture
                if (!timeoutFuture.isDone) scheduledTimeout.scheduledFuture.cancel(true)
                scheduledTimeout.retryCount
            } else 0
            val scheduledFuture = scheduleTimeoutException(flow, timeoutSeconds)
            timedFlows[flowId] = ScheduledTimeout(scheduledFuture, retryCount)
        } else {
            logger.warn("Unable to schedule timeout for flow $flowId – flow not found.")
        }
    }

    /** Schedules a [FlowTimeoutException] to be fired in order to restart the flow. */
    private fun scheduleTimeoutException(flow: Flow, delay: Long): ScheduledFuture<*> {
        return with(serviceHub.configuration.flowTimeout) {
            timeoutScheduler.schedule({
                val event = Event.Error(FlowTimeoutException())
                flow.fiber.scheduleEvent(event)
            }, delay, TimeUnit.SECONDS)
        }
    }

    private fun calculateDefaultTimeoutSeconds(retryCount: Int): Long {
        return with(serviceHub.configuration.flowTimeout) {
            val timeoutDelaySeconds = timeout.seconds * Math.pow(backoffBase, min(retryCount, maxRestartCount).toDouble()).toLong()
            maxOf(1L, ((1.0 + Math.random()) * timeoutDelaySeconds / 2).toLong())
        }
    }

    /**
     * Cancels any scheduled flow timeout for [flowId].
     *
     * Assumes lock is taken on the [InnerState].
     */
    private fun InnerState.cancelTimeoutIfScheduled(flowId: StateMachineRunId) {
        timedFlows[flowId]?.let { (future, _) ->
            if (!future.isDone) future.cancel(true)
            timedFlows.remove(flowId)
        }
    }

    private fun verifyFlowLogicIsSuspendable(logic: FlowLogic<Any?>) {
        // Quasar requires (in Java 8) that at least the call method be annotated suspendable. Unfortunately, it's
        // easy to forget to add this when creating a new flow, so we check here to give the user a better error.
        //
        // The Kotlin compiler can sometimes generate a synthetic bridge method from a single call declaration, which
        // forwards to the void method and then returns Unit. However annotations do not get copied across to this
        // bridge, so we have to do a more complex scan here.
        val call = logic.javaClass.methods.first { !it.isSynthetic && it.name == "call" && it.parameterCount == 0 }
        if (call.getAnnotation(Suspendable::class.java) == null) {
            throw FlowException("${logic.javaClass.name}.call() is not annotated as @Suspendable. Please fix this.")
        }
    }

    private fun createTransientValues(id: StateMachineRunId, resultFuture: CordaFuture<Any?>): FlowStateMachineImpl.TransientValues {
        return FlowStateMachineImpl.TransientValues(
                eventQueue = Channels.newChannel(-1, Channels.OverflowPolicy.BLOCK),
                resultFuture = resultFuture,
                database = database,
                transitionExecutor = transitionExecutor,
                actionExecutor = actionExecutor!!,
                stateMachine = StateMachine(id, secureRandom),
                serviceHub = serviceHub,
                checkpointSerializationContext = checkpointSerializationContext!!,
                unfinishedFibers = unfinishedFibers,
                waitTimeUpdateHook = { flowId, timeout -> resetCustomTimeout(flowId, timeout) }
        )
    }

    private inline fun <reified T : Any> tryCheckpointDeserialize(bytes: SerializedBytes<T>, flowId: StateMachineRunId): T? {
        return try {
            bytes.checkpointDeserialize(context = checkpointSerializationContext!!)
        } catch (e: Exception) {
            logger.error("Unable to deserialize checkpoint for flow $flowId. Something is very wrong and this flow will be ignored.", e)
            null
        }
    }

    private fun createFlowFromCheckpoint(
            id: StateMachineRunId,
            serializedCheckpoint: SerializedBytes<Checkpoint>,
            isAnyCheckpointPersisted: Boolean,
            isStartIdempotent: Boolean,
            initialDeduplicationHandler: DeduplicationHandler?
    ): Flow? {
        val checkpoint = tryCheckpointDeserialize(serializedCheckpoint, id) ?: return null
        val flowState = checkpoint.flowState
        val resultFuture = openFuture<Any?>()
        val fiber = when (flowState) {
            is FlowState.Unstarted -> {
                val logic = tryCheckpointDeserialize(flowState.frozenFlowLogic, id) ?: return null
                val state = StateMachineState(
                        checkpoint = checkpoint,
                        pendingDeduplicationHandlers = initialDeduplicationHandler?.let { listOf(it) } ?: emptyList(),
                        isFlowResumed = false,
                        isTransactionTracked = false,
                        isAnyCheckpointPersisted = isAnyCheckpointPersisted,
                        isStartIdempotent = isStartIdempotent,
                        isRemoved = false,
                        flowLogic = logic,
                        senderUUID = null
                )
                val fiber = FlowStateMachineImpl(id, logic, scheduler)
                fiber.transientValues = TransientReference(createTransientValues(id, resultFuture))
                fiber.transientState = TransientReference(state)
                fiber.logic.stateMachine = fiber
                fiber
            }
            is FlowState.Started -> {
                val fiber = tryCheckpointDeserialize(flowState.frozenFiber, id) ?: return null
                val state = StateMachineState(
                        checkpoint = checkpoint,
                        pendingDeduplicationHandlers = initialDeduplicationHandler?.let { listOf(it) } ?: emptyList(),
                        isFlowResumed = false,
                        isTransactionTracked = false,
                        isAnyCheckpointPersisted = isAnyCheckpointPersisted,
                        isStartIdempotent = isStartIdempotent,
                        isRemoved = false,
                        flowLogic = fiber.logic,
                        senderUUID = null
                )
                fiber.transientValues = TransientReference(createTransientValues(id, resultFuture))
                fiber.transientState = TransientReference(state)
                fiber.logic.stateMachine = fiber
                fiber
            }
        }

        verifyFlowLogicIsSuspendable(fiber.logic)

        return Flow(fiber, resultFuture)
    }

    private fun addAndStartFlow(id: StateMachineRunId, flow: Flow) {
        val checkpoint = flow.fiber.snapshot().checkpoint
        for (sessionId in getFlowSessionIds(checkpoint)) {
            sessionToFlow[sessionId] = id
        }
        mutex.locked {
            if (stopping) {
                startedFutures[id]?.setException(IllegalStateException("Will not start flow as SMM is stopping"))
                logger.trace("Not resuming as SMM is stopping.")
            } else {
                val oldFlow = flows.put(id, flow)
                if (oldFlow == null) {
                    incrementLiveFibers()
                    unfinishedFibers.countUp()
                } else {
                    oldFlow.resultFuture.captureLater(flow.resultFuture)
                }
                val flowLogic = flow.fiber.logic
                if (flowLogic.isEnabledTimedFlow()) scheduleTimeout(id)
                flow.fiber.scheduleEvent(Event.DoRemainingWork)
                when (checkpoint.flowState) {
                    is FlowState.Unstarted -> {
                        flow.fiber.start()
                    }
                    is FlowState.Started -> {
                        Fiber.unparkDeserialized(flow.fiber, scheduler)
                    }
                }
            }
        }
    }

    private fun getFlowSessionIds(checkpoint: Checkpoint): Set<SessionId> {
        val initiatedFlowStart = (checkpoint.flowState as? FlowState.Unstarted)?.flowStart as? FlowStart.Initiated
        return if (initiatedFlowStart == null) {
            checkpoint.sessions.keys
        } else {
            checkpoint.sessions.keys + initiatedFlowStart.initiatedSessionId
        }
    }

    private fun makeActionExecutor(checkpointSerializationContext: CheckpointSerializationContext): ActionExecutor {
        return ActionExecutorImpl(
                serviceHub,
                checkpointStorage,
                flowMessaging,
                this,
                checkpointSerializationContext,
                metrics
        )
    }

    private fun makeTransitionExecutor(): TransitionExecutor {
        val interceptors = ArrayList<TransitionInterceptor>()
        interceptors.add { HospitalisingInterceptor(flowHospital, it) }
        if (serviceHub.configuration.devMode) {
            interceptors.add { DumpHistoryOnErrorInterceptor(it) }
        }
        if (serviceHub.configuration.shouldCheckCheckpoints()) {
            interceptors.add { FiberDeserializationCheckingInterceptor(fiberDeserializationChecker!!, it) }
        }
        if (logger.isDebugEnabled) {
            interceptors.add { PrintingInterceptor(it) }
        }
        val transitionExecutor: TransitionExecutor = TransitionExecutorImpl(secureRandom, database)
        return interceptors.fold(transitionExecutor) { executor, interceptor -> interceptor(executor) }
    }

    private fun InnerState.removeFlowOrderly(
            flow: Flow,
            removalReason: FlowRemovalReason.OrderlyFinish,
            lastState: StateMachineState
    ) {
        drainFlowEventQueue(flow)
        // final sanity checks
        require(lastState.pendingDeduplicationHandlers.isEmpty()) { "Flow cannot be removed until all pending deduplications have completed" }
        require(lastState.isRemoved) { "Flow must be in removable state before removal" }
        require(lastState.checkpoint.subFlowStack.size == 1) { "Checkpointed stack must be empty" }
        require(flow.fiber.id !in sessionToFlow.values) { "Flow fibre must not be needed by an existing session" }
        flow.resultFuture.set(removalReason.flowReturnValue)
        lastState.flowLogic.progressTracker?.currentStep = ProgressTracker.DONE
        changesPublisher.onNext(StateMachineManager.Change.Removed(lastState.flowLogic, Try.Success(removalReason.flowReturnValue)))
    }

    private fun InnerState.removeFlowError(
            flow: Flow,
            removalReason: FlowRemovalReason.ErrorFinish,
            lastState: StateMachineState
    ) {
        drainFlowEventQueue(flow)
        val flowError = removalReason.flowErrors[0] // TODO what to do with several?
        val exception = flowError.exception
        (exception as? FlowException)?.originalErrorId = flowError.errorId
        flow.resultFuture.setException(exception)
        lastState.flowLogic.progressTracker?.endWithError(exception)
        changesPublisher.onNext(StateMachineManager.Change.Removed(lastState.flowLogic, Try.Failure<Nothing>(exception)))
    }

    // The flow's event queue may be non-empty in case it shut down abruptly. We handle outstanding events here.
    private fun drainFlowEventQueue(flow: Flow) {
        while (true) {
            val event = flow.fiber.transientValues!!.value.eventQueue.tryReceive() ?: return
            when (event) {
                is Event.DoRemainingWork -> {}
                is Event.DeliverSessionMessage -> {
                    // Acknowledge the message so it doesn't leak in the broker.
                    event.deduplicationHandler.afterDatabaseTransaction()
                    when (event.sessionMessage.payload) {
                        EndSessionMessage -> {
                            logger.debug { "Unhandled message ${event.sessionMessage} due to flow shutting down" }
                        }
                        else -> {
                            logger.warn("Unhandled message ${event.sessionMessage} due to flow shutting down")
                        }
                    }
                }
                else -> {
                    logger.warn("Unhandled event $event due to flow shutting down")
                }
            }
        }
    }
}
