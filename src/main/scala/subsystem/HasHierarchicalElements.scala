// See LICENSE.SiFive for license details.

package freechips.rocketchip.subsystem

import chisel3._
import chisel3.dontTouch
import org.chipsalliance.cde.config.{Field, Parameters}
import freechips.rocketchip.devices.debug.{TLDebugModule, HasPeripheryDebug}
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci.{ClockGroup, ResetCrossingType, ClockGroupNode}
import freechips.rocketchip.util._
import freechips.rocketchip.rocket.{TracedInstruction}
import scala.collection.immutable.SortedMap

/** A default implementation of parameterizing the connectivity of the port where the tile is the master.
  *   Optional timing buffers and/or an optional CacheCork can be inserted in the interconnect's clock domain.
  */
case class HierarchicalElementMasterPortParams(
  buffers: Int = 0,
  cork: Option[Boolean] = None,
  where: TLBusWrapperLocation = SBUS
) extends HierarchicalElementPortParamsLike {
  def injectNode(context: Attachable)(implicit p: Parameters): TLNode = {
    (TLBuffer.chainNode(buffers) :=* cork.map { u => TLCacheCork(unsafe = u) } .getOrElse { TLTempNode() })
  }
}

object HierarchicalElementMasterPortParams {
  def locationDefault(loc: HierarchicalLocation) = loc match {
    case InSubsystem => HierarchicalElementMasterPortParams()
    case InCluster(clusterId) => HierarchicalElementMasterPortParams(where=CSBUS(clusterId))
  }
}

/** A default implementation of parameterizing the connectivity of the port giving access to slaves inside the tile.
  *   Optional timing buffers and/or an optional BusBlocker adapter can be inserted in the interconnect's clock domain.
  */
case class HierarchicalElementSlavePortParams(
  buffers: Int = 0,
  blockerCtrlAddr: Option[BigInt] = None,
  blockerCtrlWhere: TLBusWrapperLocation = CBUS,
  where: TLBusWrapperLocation = CBUS
) extends HierarchicalElementPortParamsLike {
  def injectNode(context: Attachable)(implicit p: Parameters): TLNode = {
    val controlBus = context.locateTLBusWrapper(where)
    val blockerBus = context.locateTLBusWrapper(blockerCtrlWhere)
    blockerCtrlAddr
      .map { BasicBusBlockerParams(_, blockerBus.beatBytes, controlBus.beatBytes) }
      .map { bbbp =>
        val blocker = LazyModule(new BasicBusBlocker(bbbp))
        blockerBus.coupleTo("tile_slave_port_bus_blocker") { blocker.controlNode := TLFragmenter(blockerBus) := _ }
        blocker.node :*= TLBuffer.chainNode(buffers)
      } .getOrElse { TLBuffer.chainNode(buffers) }
  }
}

object HierarchicalElementSlavePortParams {
  def locationDefault(loc: HierarchicalLocation) = loc match {
    case InSubsystem => HierarchicalElementSlavePortParams()
    case InCluster(clusterId) => HierarchicalElementSlavePortParams(where=CCBUS(clusterId), blockerCtrlWhere=CCBUS(clusterId))
  }
}

/** InstantiatesTiles adds a Config-urable sequence of HierarchicalElements of any type
  *   to the subsystem class into which it is mixed.
  */
trait InstantiatesHierarchicalElements { this: LazyModule with Attachable =>
  val location: HierarchicalLocation

  /** Record the order in which to instantiate all tiles, based on statically-assigned ids.
    *
    * Note that these ids, which are often used as the tiles' default hartid input,
    * may or may not be those actually reflected at runtime in e.g. the $mhartid CSR
    */
  val tileAttachParams: Seq[CanAttachTile] = p(TilesLocated(location)).sortBy(_.tileParams.tileId)
  val tileParams: Seq[TileParams] = tileAttachParams.map(_.tileParams)
  val tileCrossingTypes: Seq[ClockCrossingType] = tileAttachParams.map(_.crossingParams.crossingType)

  /** The actual list of instantiated tiles in this block. */
  val tile_prci_domains: SortedMap[Int, TilePRCIDomain[_]] = tileAttachParams.foldLeft(SortedMap[Int, TilePRCIDomain[_]]()) {
    case (instantiated, params) => instantiated + (params.tileParams.tileId -> params.instantiate(tileParams, instantiated)(p))
  }

  val clusterAttachParams: Seq[CanAttachCluster] = p(ClustersLocated(location)).sortBy(_.clusterParams.clusterId)
  val clusterParams: Seq[ClusterParams] = clusterAttachParams.map(_.clusterParams)
  val clusterCrossingTypes: Seq[ClockCrossingType] = clusterAttachParams.map(_.crossingParams.crossingType)
  val cluster_prci_domains: SortedMap[Int, ClusterPRCIDomain] = clusterAttachParams.foldLeft(SortedMap[Int, ClusterPRCIDomain]()) {
    case (instantiated, params) => instantiated + (params.clusterParams.clusterId -> params.instantiate(clusterParams, instantiated)(p))
  }

  val element_prci_domains: Seq[HierarchicalElementPRCIDomain[_]] = tile_prci_domains.values.toSeq ++ cluster_prci_domains.values.toSeq

  val leafTiles: SortedMap[Int, BaseTile] = SortedMap(tile_prci_domains.mapValues(_.element.asInstanceOf[BaseTile]).toSeq.sortBy(_._1):_*)
  val totalTiles: SortedMap[Int, BaseTile] = (leafTiles ++ cluster_prci_domains.values.map(_.element.totalTiles).flatten)

  // Helper functions for accessing certain parameters that are popular to refer to in subsystem code
  def nLeafTiles: Int = leafTiles.size
  def nTotalTiles: Int = totalTiles.size
  def leafTileIdList: Seq[Int] = leafTiles.keys.toSeq.sorted
  def totalTileIdList: Seq[Int] = totalTiles.keys.toSeq.sorted
  def localIntCounts: SortedMap[Int, Int] = totalTiles.mapValues(_.tileParams.core.nLocalInterrupts).to(SortedMap)

  require(totalTileIdList.distinct.size == totalTiles.size, s"Every tile must be statically assigned a unique id, but got:\n${totalTileIdList}")
}

/** HasTiles instantiates and also connects a Config-urable sequence of tiles of any type to subsystem interconnect resources. */
trait HasHierarchicalElements extends DefaultHierarchicalElementContextType
{ this: LazyModule with Attachable with InstantiatesHierarchicalElements =>
  implicit val p: Parameters

  // connect all the tiles to interconnect attachment points made available in this subsystem context
  tileAttachParams.foreach { params =>
    params.connect(tile_prci_domains(params.tileParams.tileId).asInstanceOf[TilePRCIDomain[params.TileType]], this.asInstanceOf[params.TileContextType])
  }
  clusterAttachParams.foreach { params =>
    params.connect(cluster_prci_domains(params.clusterParams.clusterId).asInstanceOf[ClusterPRCIDomain], this.asInstanceOf[params.ClusterContextType])
  }
}

/** Provides some Chisel connectivity to certain tile IOs
  * This trait is intended for the root subsystem
  */
trait HasHierarchicalElementsRootContextModuleImp extends LazyRawModuleImp {
  val outer: InstantiatesHierarchicalElements with HasHierarchicalElements with HasHierarchicalElementsRootContext with HasTileInputConstants

  val reset_vector = outer.tileResetVectorIONodes.zipWithIndex.map { case (n, i) => n.makeIO(s"reset_vector_$i") }
  val tile_hartids = outer.tileHartIdIONodes.zipWithIndex.map { case (n, i) => n.makeIO(s"tile_hartids_$i") }

  val meip = if (outer.meipIONode.isDefined) Some(IO(Input(Vec(outer.meipIONode.get.out.size, Bool())))) else None
  meip.foreach { m =>
    m.zipWithIndex.foreach{ case (pin, i) =>
      (outer.meipIONode.get.out(i)._1)(0) := pin
    }
  }
  val seip = if (outer.seipIONode.isDefined) Some(IO(Input(Vec(outer.seipIONode.get.out.size, Bool())))) else None
  seip.foreach { s =>
    s.zipWithIndex.foreach{ case (pin, i) =>
      (outer.seipIONode.get.out(i)._1)(0) := pin
    }
  }
  val nmi = outer.nmiIONodes.map { case (i, node) =>
    node.makeIO(s"nmi_$i")
  }
}

/** Most tile types require only these traits in order for their standardized connect functions to apply.
  *
  *    BaseTiles subtypes with different needs can extend this trait to provide themselves with
  *    additional external connection points.
  */
trait DefaultHierarchicalElementContextType
    extends Attachable
    with HasTileNotificationSinks
{ this: LazyModule with Attachable =>
  val msipNodes: SortedMap[Int, IntNode]
  val meipNodes: SortedMap[Int, IntNode]
  val seipNodes: SortedMap[Int, IntNode]
  val tileToPlicNodes: SortedMap[Int, IntNode]
  val debugNodes: SortedMap[Int, IntSyncNode]
  val nmiNodes: SortedMap[Int, BundleBridgeNode[NMI]]
  val tileHartIdNodes: SortedMap[Int, BundleBridgeNode[UInt]]
  val tileResetVectorNodes: SortedMap[Int, BundleBridgeNode[UInt]]
  val traceCoreNodes: SortedMap[Int, BundleBridgeNode[TraceCoreInterface]]
  val traceNodes: SortedMap[Int, BundleBridgeNode[TraceBundle]]
}

/** This trait provides the tile attachment context for the root (outermost) subsystem */
trait HasHierarchicalElementsRootContext
{ this: HasHierarchicalElements
    with HasTileNotificationSinks
    with InstantiatesHierarchicalElements =>

  val clintOpt: Option[CLINT]
  val plicOpt: Option[TLPLIC]
  val debugOpt: Option[TLDebugModule]

  val msipNodes: SortedMap[Int, IntNode] = (0 until nTotalTiles).map { i =>
    (i, IntEphemeralNode())
  }.to(SortedMap)
  msipNodes.foreach {
    _._2 := clintOpt.map(_.intnode).getOrElse(NullIntSource(sources = CLINTConsts.ints))
  }

  val meipIONode = Option.when(plicOpt.isEmpty)(IntNexusNode(
    sourceFn = { _ => IntSourcePortParameters(Seq(IntSourceParameters(1))) },
    sinkFn   = { _ => IntSinkPortParameters(Seq(IntSinkParameters())) },
    outputRequiresInput = false,
    inputRequiresOutput = false))
  val meipNodes: SortedMap[Int, IntNode] = (0 until nTotalTiles).map { i =>
    (i, IntEphemeralNode() := plicOpt.map(_.intnode).getOrElse(meipIONode.get))
  }.to(SortedMap)

  val seipIONode = Option.when(plicOpt.isEmpty)(IntNexusNode(
    sourceFn = { _ => IntSourcePortParameters(Seq(IntSourceParameters(1))) },
    sinkFn   = { _ => IntSinkPortParameters(Seq(IntSinkParameters())) },
    outputRequiresInput = false,
    inputRequiresOutput = false))
  val seipNodes: SortedMap[Int, IntNode] = totalTiles.filter { case (_, t) => t.tileParams.core.hasSupervisorMode }
    .mapValues( _ => IntEphemeralNode() := plicOpt.map(_.intnode).getOrElse(seipIONode.get)).to(SortedMap)

  val tileToPlicNodes: SortedMap[Int, IntNode] = (0 until nTotalTiles).map { i =>
    plicOpt.map(o => (i, o.intnode :=* IntEphemeralNode()))
  }.flatten.to(SortedMap)

  val debugNodes: SortedMap[Int, IntSyncNode] = (0 until nTotalTiles).map { i =>
    (i, IntSyncIdentityNode())
  }.to(SortedMap)

  debugNodes.foreach { case (hartid, node) =>
    node := debugOpt.map(_.intnode).getOrElse(IntSyncCrossingSource() := NullIntSource())
  }

  val nmiHarts = totalTiles.filter { case (_, t) => t.tileParams.core.useNMI }.keys
  val nmiIONodes = nmiHarts.map { i => (i, BundleBridgeSource[NMI]()) }.to(SortedMap)
  val nmiNodes: SortedMap[Int, BundleBridgeNode[NMI]] = nmiIONodes.map { case (i, n) =>
    (i, BundleBridgeEphemeralNode[NMI]() := n)
  }.to(SortedMap)

  val traceCoreNodes: SortedMap[Int, BundleBridgeSink[TraceCoreInterface]] = (0 until nTotalTiles).map { i => (i, BundleBridgeSink[TraceCoreInterface]()) }.to(SortedMap)
  val traceNodes: SortedMap[Int, BundleBridgeSink[TraceBundle]] = (0 until nTotalTiles).map { i => (i, BundleBridgeSink[TraceBundle]()) }.to(SortedMap)
}
