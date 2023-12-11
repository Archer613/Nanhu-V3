package cachetest

import coupledL2._
import circt.stage.{ChiselStage, FirtoolOption}
import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import chisel3.stage.ChiselGeneratorAnnotation
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts.{IntSinkNode, IntSinkPortSimple}
import freechips.rocketchip.interrupts.{IntSourceNode, IntSourcePortSimple}
import coupledL2.prefetch._
import coupledL2.utils.HasPerfEvents
import huancun.{HuanCun, HCCacheParameters, HCCacheParamsKey, CacheParameters, CacheCtrl}
import xs.utils.GTimer
import xs.utils.perf.{DebugOptions,DebugOptionsKey}


class TestTop_fullSys_1Core()(implicit p: Parameters) extends LazyModule {
  val delayFactor = 0.2
  val cacheParams = p(L2ParamKey)

  val NumCores = 1
  val nrL2 = NumCores

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Nil,
        requestFields = Seq(AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l2xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0xffffffffL), beatBytes = 32)) // Normal rtl-based memory
  // val ram = LazyModule(new coupledL2.TLRAM(AddressSet(0, 0xffffffffffffL), beatBytes = 32)) // DPI-C memory
  var master_nodes: Seq[TLClientNode] = Seq() // TODO
  
  // val nullNode = LazyModule(new SppSenderNull)
  val l2List = (0 until nrL2).map{i =>
    val l1d = createClientNode(s"l1d$i", 32)
    val l1i = TLClientNode(Seq(
      TLMasterPortParameters.v1(
        clients = Seq(TLMasterParameters.v1(
          name = s"l1i$i",
          sourceId = IdRange(0, 32)
        ))
      )
    ))
    master_nodes = master_nodes ++ Seq(l1d, l1i) // TODO

    val l1xbar = TLXbar()
    val l2node = LazyModule(new CoupledL2()(new Config((_, _, _) => {
      case L2ParamKey => L2Param(
        name = s"l2$i",
        ways = 8,
        sets = 256,
        clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
        echoField = Seq(huancun.DirtyField()),
        // prefetch = Some(BOPParameters(rrTableEntries = 16,rrTagBits = 6))
        prefetch = None,
        /* del L2 prefetche recv option, move into: prefetch =  PrefetchReceiverParams
        prefetch options:
          SPPParameters          => spp only
          BOPParameters          => bop only
          PrefetchReceiverParams => sms+bop
          HyperPrefetchParams    => spp+bop+sms
        */
        sppMultiLevelRefill = None,
        /*must has spp, otherwise Assert Fail
        sppMultiLevelRefill options:
        PrefetchReceiverParams() => spp has cross level refill
        None                     => spp only refill L2 
        */
      )
      case DebugOptionsKey => DebugOptions()
    })))
    l1xbar := TLBuffer() := l1i
    l1xbar := TLBuffer() := l1d
    // l2node.pf_recv_node match{
    //   case Some(l2Recv) => 
    //     val l1_sms_send_0_node = LazyModule(new PrefetchSmsOuterNode)
    //     l2Recv := l1_sms_send_0_node.outNode
    //   case None =>
    // }
    l2xbar := TLBuffer() := l2node.node := l1xbar
    l2node // return l2 list
  }

  val l3 = LazyModule(new HuanCun()(new Config((_, _, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = 4,
      sets = 2048,
      inclusive = false,
      clientCaches = Seq(CacheParameters(sets = 32, ways = 4, blockGranularity = log2Ceil(32), name = "L2")),
      sramClkDivBy2 = true,
      sramDepthDiv = 4,
      dataBytes = 8,
      simulation = true,
      hasMbist = false,
      prefetch = None,
      prefetchRecv = None, // None, //Some(huancun.prefetch.PrefetchReceiverParams()),
      tagECC = Some("secded"),
      dataECC = Some("secded"),
      ctrl = Some(huancun.CacheCtrl(
//        address = 0x3900_0000
        address = 0x390_0000
      ))
    )
    case DebugOptionsKey => DebugOptions()
  })))

  // println(f"pf_l3recv_node connecting to l3pf_RecvXbar out")
  // val sppHasCrossLevelRefillOpt = p(L2ParamKey).sppMultiLevelRefill
  // println(f"SPP cross level refill: ${sppHasCrossLevelRefillOpt} ")
  // sppHasCrossLevelRefillOpt match{
  //   case Some(x) =>
  //     val l3pf_RecvXbar = LazyModule(new PrefetchReceiverXbar(NumCores))
  //     l2List.zipWithIndex.foreach {
  //       case (l2, i) =>
  //         l2.spp_send_node match {
  //           case Some(l2Send) =>
  //             l3pf_RecvXbar.inNode(i) := l2Send
  //             println(f"spp_send_node${i} connecting to l3pf_RecvXbar")
  //           case None =>
  //       }
  //     }
  //     println(f"pf_l3recv_node connecting to l3pf_RecvXbar out")
  //     l3.pf_l3recv_node.map(l3_recv =>  l3_recv:= l3pf_RecvXbar.outNode.head)
  //   case None =>
  // }
  val ctrl_node = TLClientNode(Seq(TLMasterPortParameters.v2(
      Seq(TLMasterParameters.v1(
        name = "ctrl",
        sourceId = IdRange(0, 16),
        supportsProbe = TransferSizes.none
      )),
      channelBytes = TLChannelBeatBytes(8), // 64bits
      minLatency = 1,
      echoFields = Nil,
    )))

  val l3_ecc_int_sink = IntSinkNode(IntSinkPortSimple(1, 1))
  l3.ctlnode.foreach(_ := TLBuffer() := ctrl_node)
  l3.intnode.foreach(l3_ecc_int_sink := _)

  val l2_ecc_int_sinks = Seq.fill(nrL2)(IntSinkNode(IntSinkPortSimple(1, 1)))
  l2List.map(_.intNode).zip(l2_ecc_int_sinks).foreach{ 
    case(source, sink) => sink := source
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(32, 64) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      l3.node :=* l2xbar

  lazy val module = new LazyModuleImp(this) with HasPerfEvents{
    master_nodes.zipWithIndex.foreach {
      case (node, i) =>
        node.makeIOs()(ValName(s"master_port_$i"))
    }
    // l3FrontendAXI4Node.makeIOs()(ValName("dma_port"))
    ctrl_node.makeIOs()(ValName("cmo_port"))
    l3_ecc_int_sink.makeIOs()(ValName("l3_int_port"))

    l2_ecc_int_sinks.zipWithIndex.foreach{ case(sink, i) => sink.makeIOs()(ValName("l2_int_port_"+i))}


    val io = IO(new Bundle{
      val perfClean = Input(Bool())
      val perfDump = Input(Bool())
    })

    l2List.foreach(_.module.io.dfx_reset.scan_mode := false.B)
    l2List.foreach(_.module.io.dfx_reset.lgc_rst_n := true.B.asAsyncReset)
    l2List.foreach(_.module.io.dfx_reset.mode := false.B)
    
    val logTimestamp = WireInit(0.U(64.W))
    val perfClean = WireInit(false.B)
    val perfDump = WireInit(false.B)
  
    perfClean := io.perfClean
    perfDump := io.perfDump
  
    val timer = GTimer()

    logTimestamp := timer

    val perfEvents = (l2List.map(_.module)).flatMap(_.getPerfEvents)
    generatePerfEvent()
  }
}


object TestTop_fullSys_1Core extends App {
  val config = new Config((_, _, _) => {
    case L2ParamKey => L2Param(
      clientCaches = Seq(L1Param(aliasBitsOpt = Some(2))),
      echoField = Seq(DirtyField()),
      // prefetch = Some(BOPParameters(rrTableEntries = 16,rrTagBits = 6))
      prefetch = Some(HyperPrefetchParams()), /*
      del L2 prefetche recv option, move into: prefetch =  PrefetchReceiverParams
      prefetch options:
        SPPParameters          => spp only
        BOPParameters          => bop only
        PrefetchReceiverParams => sms+bop
        HyperPrefetchParams    => spp+bop+sms
      */
      sppMultiLevelRefill = Some(coupledL2.prefetch.PrefetchReceiverParams()),
      /*must has spp, otherwise Assert Fail
      sppMultiLevelRefill options:
      PrefetchReceiverParams() => spp has cross level refill
      None                     => spp only refill L2
      */
    )
    case HCCacheParamsKey => HCCacheParameters(
      echoField = Seq(DirtyField())
    )
    case DebugOptionsKey => DebugOptions()
  })
  val top = DisableMonitors(p => LazyModule(new TestTop_fullSys_1Core()(p)))(config)

  (new ChiselStage).execute(Array("--target", "verilog") ++ args, Seq(
    ChiselGeneratorAnnotation(() => top.module),
    FirtoolOption("--disable-annotation-unknown")
  ))
}
