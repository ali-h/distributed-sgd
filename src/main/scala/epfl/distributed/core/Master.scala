package epfl.distributed.core

import com.google.protobuf.empty.Empty
import com.typesafe.scalalogging.Logger
import epfl.distributed.core.core.SlaveGrpc.SlaveStub
import epfl.distributed.core.core._
import epfl.distributed.math.Vec
import epfl.distributed.utils.Pool
import io.grpc.Server
import spire.math.Number

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.stm._
import scala.concurrent.{ExecutionContextExecutorService, Future, Promise}
import scala.util.{Failure, Success}

abstract class AbstractMaster(node: Node, data: Array[(Vec, Int)]) {

  protected val masterGrpcImpl: AbstractMasterGrpc

  implicit val ec: ExecutionContextExecutorService = Pool.newFixedExecutor()

  //Without the lazy, we get an initialized field exception
  private lazy val server: Server = newServer(MasterGrpc.bindService(masterGrpcImpl, ec), node.port)

  protected val log = Logger(s"master-${pretty(node)}")

  protected val slaves = TrieMap[Node, SlaveStub]()

  // need to change this
  private val slaveJoinCallbacks = mutable.ListBuffer.empty[Int => Unit]

  def start(): Unit = {
    require(!ec.isShutdown)
    server.start()
    log.info("started")
  }

  def stop(): Unit = {
    server.shutdown()
    server.awaitTermination()
    ec.shutdown()
    log.info("stopped")
  }

  def awaitTermination(): Unit = {
    log.info("waiting")
    server.awaitTermination()
  }

  def onSlaveJoin(callback: Int => Unit): Unit = {
    slaveJoinCallbacks += callback
  }

  def forward(weights: Vec): Future[Iterable[Number]] = {
    val workers = slaves.values
    val piece   = Math.floorDiv(data.length, workers.size)

    val work = workers.zipWithIndex.map {
      case (worker, i) =>
        val sample = i * piece
        val req    = ForwardRequest(sample until (sample + piece), weights)
        worker.forward(req)
    }

    Future.sequence(work).map(_.flatMap(_.predictions))
  }

  def backward(epochs: Int, batchSize: Int = 1, weights: Vec): Future[Vec] = {

    def loop[T](on: Seq[T])(init: Vec)(apply: (Vec, T) => Future[Vec]): Future[Vec] =
      on.foldLeft(Future.successful(init)) {
        case (itWeights, it) =>
          itWeights.flatMap(w => apply(w, it))
      }

    /*
      Need to improve:
      - data idx attributions per node
      - randomness of idx between epochs
      - cleaner encoding/decoding between vec implement and grpc message data
      - stepsize

      How can this fit into the async mode?
     */

    log.info(s"dsgd start")

    val init             = weights
    val workersWithIndex = slaves.values.zipWithIndex
    val piece            = Math.floorDiv(data.length, workersWithIndex.size)

    val result = loop(1 to epochs)(init) { // epoch
      case (epochWeight, epoch) =>
        log.info(s"epoch $epoch")

        loop(0 until piece by batchSize)(epochWeight) { // batch
          case (batchWeights, batch) =>
            log.debug(s"step ${batch + 1} / $piece")

            val work = workersWithIndex.map {
              case (worker, i) =>
                val sample = i * piece + batch

                val req =
                  GradientRequest(sample until Math.min(sample + batchSize, i * piece + piece), 0.1, 0, batchWeights)
                worker.gradient(req)
            }
            Future
              .sequence(work)
              .map { res =>
                val grad        = Vec.mean(res.map(_.grad))
                val durations   = res.map(x => x.terminatedAt - x.startedAt)
                val durationMax = durations.max / 1000.0
                val durationMin = durations.min / 1000.0
                val durationAvg = durations.sum / 1000.0 / durations.size
                val sparsity    = 100 * grad.sparsity()
                log.trace(
                    f"$epoch:$batch sparsity $sparsity%.1f%% duration ($durationMin%.3f, $durationAvg%.3f, $durationMax%.3f)")
                batchWeights + grad
              }
        }

    }
    log.debug("Work done, waiting result")

    result.foreach(_ => log.info(s"dsgd end"))
    result
  }

  def computeLoss(weights: Vec): Future[Number] = {
    forward(weights)
      .map(
          _.zip(data)
            .map { case (p, (_, y)) => (p - y) ** 2 }
            .reduce(_ + _) / data.length)
  }

  abstract class AbstractMasterGrpc extends MasterGrpc.Master {

    def registerSlave(node: Node): Future[Ack] = {
      val stub = SlaveGrpc.stub(newChannel(node.host, node.port))

      val slavesSnap = slaves.readOnlySnapshot()
      slaves.put(node, stub)

      log.info(s"new slave ${pretty(node)}")

      slavesSnap.foreach {
        case (otherNode, otherStub) =>
          otherStub.registerSlave(node)
          stub.registerSlave(otherNode)
      }

      val ack = Future.successful(Ack())
      ack.onComplete(_ => slaveJoinCallbacks.foreach(_(slavesSnap.size + 1)))
      ack
    }

    def unregisterSlave(node: Node): Future[Ack] = {
      slaves.remove(node)

      log.info(s"exit slave ${pretty(node)}")

      slaves.values.foreach(_.unregisterSlave(node))

      Future.successful(Ack())
    }
  }
}

class SyncMaster(node: Node, data: Array[(Vec, Int)]) extends AbstractMaster(node, data) {

  override protected val masterGrpcImpl = new SyncMasterGrpcImpl

  class SyncMasterGrpcImpl extends AbstractMasterGrpc {

    override def updateGrad(request: GradUpdate): Future[Ack] =
      throw new UnsupportedOperationException("Synchronous master cannont perform async operation update grad")
  }
}

class AsyncMaster(node: Node, data: Array[(Vec, Int)]) extends AbstractMaster(node, data) {

  override protected val masterGrpcImpl = new AsyncMasterGrpcImpl

  protected case class AsyncConfig(initialWeights: Vec,
                                   maxSteps: Int,
                                   stoppingCriterion: Seq[Number] => Boolean,
                                   batchSize: Int,
                                   splitStrategy: (Array[(Vec, Int)], Int) => Seq[Seq[Int]],
                                   checkEvery: Int)

  /**
    * Starts the async computation of the weights
    *
    * @param initialWeights The initial weights
    * @param stoppingCriterion A function receiving (initial loss, current loss) and outputting whether to stop the computation
    * @param splitStrategy A function from (data, number workers) to a sequence of the assigned samples for each worker
    * @param checkEvery The number of gradient updates received by the master between loss checks
    *
    * @return The computed weights
    */
  def run(initialWeights: Vec,
          maxSteps: Int,
          stoppingCriterion: Seq[Number] => Boolean,
          batchSize: Int,
          splitStrategy: (Array[(Vec, Int)], Int) => Seq[Seq[Int]],
          checkEvery: Int = 100): Future[Vec] = {
    atomic { implicit txn =>
      if (masterGrpcImpl.asyncWeightsPromise().nonEmpty) {
        Future.failed(new IllegalStateException("Cannot start async computation: a computation is already running"))
      }
      else {
        val weightsPromise = Promise[Vec]
        masterGrpcImpl.initState(
            initialWeights,
            AsyncConfig(initialWeights, maxSteps, stoppingCriterion, batchSize, splitStrategy, checkEvery),
            weightsPromise)

        val workers = slaves.values
        val split   = splitStrategy(data, workers.size)

        workers.zip(split).foreach {
          case (slave, assignment) => slave.initAsync(AsyncInit(initialWeights, assignment, batchSize))
        }
        weightsPromise.future
      }
    }
  }

  class AsyncMasterGrpcImpl extends AbstractMasterGrpc {

    private[AsyncMaster] val asyncWeightsPromise = Ref(Option.empty[Promise[Vec]])
    private val gradState                        = Ref(Vec.zeros(1))
    private val asyncUpdatesCount                = Ref(0)
    private val losses                           = Ref(ArrayBuffer.empty[Number])

    private var asyncConfig: AsyncConfig = _

    def initState(initialWeights: Vec, config: AsyncConfig, weightsPromise: Promise[Vec]): Unit = atomic {
      implicit txn =>
        gradState() = initialWeights
        asyncWeightsPromise() = Some(weightsPromise)
        asyncConfig = config
    }

    def endComputation(newGrad: Vec): Unit = atomic { implicit txn =>
      slaves.values.foreach(_.stopAsync(Empty()))
      losses.set(ArrayBuffer.empty[Number])

      val weightsPromise = asyncWeightsPromise.getAndTransform(_ => None).get
      weightsPromise.complete(Success(newGrad))
    }

    def updateGrad(request: GradUpdate): Future[Ack] = {
      atomic { implicit txn =>
        asyncWeightsPromise() match {
          case Some(weightsPromise) => //There's a promise => a computation is running
            val newGrad      = gradState.transformAndGet(_ - request.gradUpdate)
            val updatesCount = asyncUpdatesCount.transformAndGet(_ + 1)

            if (updatesCount >= asyncConfig.maxSteps) {
              endComputation(newGrad)
            }
            else if (updatesCount % asyncConfig.checkEvery == 0) {
              computeLoss(newGrad).onComplete {
                case Success(loss) =>
                  val newLosses = losses.transformAndGet(_ += loss)

                  if (asyncConfig.stoppingCriterion(newLosses.toArray[Number])) { //We converged => end computation
                    endComputation(newGrad)
                  }

                case Failure(e) =>
                  log.error("Failed to compute loss: {}", e)
              }
            }

            Future.successful(Ack())
          case None =>
            Future.failed(
                new IllegalStateException("Master async computation stopped: won't accept any gradient updates"))
        }

      }
    }
  }
}

object Master {

  def sync(node: Node, data: Array[(Vec, Int)]): SyncMaster = new SyncMaster(node, data)

  def async(node: Node, data: Array[(Vec, Int)]): AsyncMaster = new AsyncMaster(node, data)

  def create(node: Node, data: Array[(Vec, Int)], async: Boolean): AbstractMaster = {
    if (async) {
      new AsyncMaster(node, data)
    }
    else {
      new SyncMaster(node, data)
    }
  }

}
