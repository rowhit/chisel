package Chisel

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap

import scala.collection.mutable.HashSet
import scala.collection.mutable.Stack

/*
object Slave {
  def apply(top: Module) = {
    val slave = Module(new Slave)
    top.parent = slave
    slave.top = top
    slave.children += top
    slave
  }
}
*/

class Slave extends Module {
  val addr_width = 5
  val data_width = 32
  val io = new Bundle {
    val in = Decoupled(Bits(width = data_width)).flip
    val out = Decoupled(Bits(width = data_width))
    val addr = Bits(INPUT, addr_width)
  }
}

class CounterBackend extends FPGABackend with SignalBackannotation {
  val shadows = new ArrayBuffer[Bits]
  val signalCounterMap = new HashMap[Node, Bits]
  val shadowCounterMap = new HashMap[Bits, Bits]
  val ops = new HashMap[Module, (Bool, Bool)]

  var counterIdx = -1
  var shadowIdx = -1

  // Define two different clock domains for the daisy chain
  val daisyClock = Module.implicitClock
  val stopClock = -daisyClock
  val emulClock = new Clock
  emulClock setName "emul_clk"

  preElaborateTransforms += ((c: Module) => generateCtrls)
  preElaborateTransforms += ((c: Module) => generateCounters(c))
  preElaborateTransforms += ((c: Module) => generateDaisyChain(c))
  preElaborateTransforms += ((c: Module) => generateSlave(c))
  transforms += ((c: Module) => c.clocks -= emulClock)
  analyses   += ((c: Module) => copyResource("wrapper.v", targetdir))

  def getTypeNode(node: Node) = {
    val typeNode = new UInt
    typeNode.isTypeNode = true
    typeNode.inputs += node
    for (consumer <- node.consumers) {
      consumer.inputs -= node
      consumer.inputs += typeNode
    }
    typeNode
  }

  def addPin(m: Module, pin: Data, name: String = "") {
    pin.component = m
    pin.isIo = true
    pin setName (name)

    pin match {
      case dio: DecoupledIO[_] => {
        dio.ready.component = m
        dio.valid.component = m
        dio.bits.component = m
        dio.ready.isIo = true
        dio.valid.isIo = true
        dio.bits.isIo = true
        dio.ready setName (name + "_ready")
        dio.valid setName (name + "_valid")
        dio.bits setName (name + "_bits")
      }
      case _ =>
    }
 
    (m.io) match {
      case io: Bundle => 
        io += pin
      case _ =>
    }
  }

  def addCounter(signal: Node, counter: Bits) = {
    val reg = counter.comp
    counterIdx += 1
    reg.component = signal.component
    reg setName "counter%d".format(counterIdx)
    signalCounterMap(signal) = counter
    counter
  }

  def addShadow(m: Module, shadow: Bits) = {
    val reg = shadow.comp
    shadowIdx += 1
    reg setName "shadow%d".format(shadowIdx)
    reg.component = m
    shadow.component = m
    shadows += shadow
    shadow
  }

  def generateCtrls {
    // Create pins
    for (m <- Module.components) {
      val stall = Bool(INPUT)
      val daisy_control = Bits(INPUT, 3)
      val daisy_out = Decoupled(Bits(width = 32))
      val in_valid = m.io("in") match { 
        case in: DecoupledIO[_] => in.ready 
        case _ => null 
      }
      def ready(i: Int) = stall && (daisy_control === Bits(i, daisy_control.getWidth)) && daisy_out.ready

      m.clock = emulClock
      m.clocks.clear
      m.clocks += daisyClock

      ready(1).getNode setName "copy"
      ready(2).getNode setName "read"

      ops(m) = (ready(1), ready(2))

      addPin(m, stall, "stall")
      addPin(m, daisy_control, "daisy_control")
      addPin(m, daisy_out, "daisy_out")

      daisy_out.valid := stall
    }
  }

  def generateCounters (m: Module) {
    ChiselError.info("Counter Backend: generate counters")

    m bfs (_.addConsumers)

    val stack = new Stack[Module]
    val walked = new HashSet[Module]
    
    m bfs { signal =>
      if (signals contains signal) {
        // ChiselError.info(emitRef(signal) + ": " + nodeToString(signal))
        val stall = signal.component.io("stall")
        val daisyControl = signal.component.io("daisy_control")
        val signalType = getTypeNode(signal)
        val signalWidth = signal.getWidth
        val counter = addCounter(signal, Reg(init = Bits(0, 32), clock = daisyClock))
        val copy = ops(signal.component)._1

        stall match {
          case isStall: Bool => {
            if (signalWidth == 1) {
              when(!isStall) {
                counter := counter + signalType
              }.elsewhen(copy) {
                counter := Bits(0, 32)
              }
            }
            // This is a bus
            else {
              val buffer = Reg(init = Bits(0, signalWidth), clock = daisyClock)
              val xor = signalType ^ buffer
              val hd = PopCount(xor)
              buffer.comp setName "buffer%d".format(counterIdx)
              buffer.comp.component = m
              buffer := signalType
              when(!isStall){
                counter := counter + hd
              }.elsewhen(copy) {
                counter := Bits(0, 32)
              }
            }
          }
          case _ =>
        }
      }
    }
  }  
  

  def generateDaisyChain(m: Module) {
    ChiselError.info("Counter Backend: generate daisy chains")
 
    // Couple counters with shadows
    m bfs { signal =>
      if (signals contains signal) {
        val shadow = addShadow(signal.component, Reg(init = Bits(0, 32), clock = daisyClock))
        val counter = signalCounterMap(signal)
 
        shadowCounterMap(shadow) = counter
      }
    }  
 
    // Daisy chain
    // Connect shodows
    // todo: hierarchical modules
    if (!shadows.isEmpty) {
      val shadow = shadows.head
      val read = ops(shadow.component)._1
      val stall = shadow.component.io("stall")
      val daisy_out = shadow.component.io("daisy_out")
      (daisy_out, stall) match {
        case (dio: DecoupledIO[_], isStall: Bool) => {
          dio.bits := shadow
        }
        case _ =>
      }
    }
    for (i <- 1 until shadows.size) {
      val shadow = shadows(i-1)
      val nextShadow = shadows(i)
      val counter = shadowCounterMap(shadow)
      val copy = ops(shadow.component)._1
      val read = ops(shadow.component)._2

      when (copy) {
        shadow := counter
      }.elsewhen (read) {
        shadow := nextShadow
      }
    }
    if (!shadows.isEmpty) {
      val shadow = shadows.last
      val counter = shadowCounterMap(shadow)
      val copy = ops(shadow.component)._1

      when (copy) {
        shadow := counter
      }
    }
  }

  def generateSlave(top: Module) {
    ChiselError.info("Counter Backend: generate the slave")

    // initialize Slave module
    val slave = Module(new Slave)
    slave.children += top
    top.parent = slave
    Module.setAsTopComponent(slave)
    slave.markComponent

    // connect pins
    val slaveAddr = slave.io("addr")
    val slaveIn = slave.io("in")
    val slaveOut = slave.io("out")
    val topInBits = Reg(init = Bits(0, 32), clock = daisyClock)
    val clkCounter = Reg(init = Bits(0, 32), clock = daisyClock)
    val first = Reg(init = Bool(true), clock = daisyClock)
    val stop = Reg(init = Bool(false), clock = stopClock)
    val notStall = clkCounter.orR
    val stall = !notStall

    topInBits.comp.component = slave
    topInBits.comp setName "top_in_bits"
    clkCounter.comp.component = slave
    clkCounter.comp setName "clk_counter" 
    first.comp.component = slave
    first.comp setName "first"
    stop.comp.component = slave
    stop.comp setName "stop" 
    stall.getNode setName "stall"

    // for input ports
    (slaveAddr, slaveIn, top.io("in")) match {
      case (addr: Bits, fromIO: DecoupledIO[_], toIO: DecoupledIO[_]) => {
        top.io("stall") := stall
        top.io("daisy_control") := addr(2,0)

        val writeAddr = addr(4,3) 
        def writeValid(i: Int) = fromIO.valid && stall && writeAddr === UInt(i, 2)
        val ready = Vec(Bool(false), Bool(true), toIO.ready, Bool(false))
        fromIO.ready := first || ready(writeAddr)

        // address = 1 => Write to the clock counter
        // address = 2 => Set inputs to the device
        (fromIO.bits, toIO.bits) match {
          case (bits: Bits, bundle: Bundle) => {
            val (name0, a) = bundle.elements(0)
            val (name1, b) = bundle.elements(1)

            // todo: stop with new inputs?
            when (writeValid(1)) {
              clkCounter := fromIO.bits
              stop := Bool(false)
            }.elsewhen (writeValid(2)) {
              topInBits := bits
              stop := Bool(false)
              // clkCounter := Bits(1, 32)
            }.elsewhen (clkCounter === Bits(1, 32)) {
              clkCounter := clkCounter - Bits(1, 32)
              stop := Bool(true)
            }.elsewhen (notStall) {
              clkCounter := clkCounter - Bits(1, 32)
              first := Bool(false)
            }
  
            a := topInBits(31, 16)
            b := topInBits(15, 0)
          }
        }

        toIO.valid := writeValid(2) || first
      }
      case _ =>
    }

    // output ports
    (slaveAddr, slaveOut, top.io("daisy_out"), top.io("out")) match {
      case (addr: Bits, 
            fromIO: DecoupledIO[_], 
            toIO1: DecoupledIO[_], 
            toIO2: ValidIO[_]) => {
        val readAddr = addr(4,3)
        def readReady(i: Int) = fromIO.ready && readAddr === UInt(i, 2)
        val bits = Vec(Bits(0), toIO1.bits, toIO2.bits, Bits(0))
        val valids = Vec(Bool(false), toIO1.valid, toIO2.valid, Bool(false))

        // address == 1 => Read from daisy chains
        // address == 2 => Read from the device result
        toIO1.ready := readReady(1) 
        // toIO2.ready := readReady(2) // no ready?
        fromIO.valid := valids(readAddr)
        fromIO.bits := bits(readAddr)
      }
      case _ =>
    }

    // emulation clock
    val clockType = getTypeNode(daisyClock)
    val clockBool = clockType.toBool
    val notStop = !stop
    val enabledClk = clockBool && notStop && notStall
    clockBool.isTypeNode = true
    enabledClk.getNode.component = slave
    enabledClk.getNode setName "emul_clk"
    daisyClock.srcClock = enabledClk.getNode

    slave.genAllMuxes
  }
}
