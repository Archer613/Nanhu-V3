/***************************************************************************************
 * Copyright (c) 2020-2023 Institute of Computing Technology, Chinese Academy of Sciences
 *
 * XiangShan is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
 * EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
 * MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

/*--------------------------------------------------------------------------------------
    Author: GMX
    Date: 2023-06-28
    email: guanmingxing@bosc.ac.cn

    Vector Instruction writeback merge
    if mask = 8'b1111_1111  => can write back
    else                    => store it in here
---------------------------------------------------------------------------------------*/

package xiangshan.vector.writeback

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._

import utils._
import xs.utils._
import xiangshan._
import xiangshan.backend._
import xiangshan.backend.rob._

class WbMergeStationEntry(implicit p: Parameters) extends XSBundle {
    val wbmask  = UInt(8.W)
    val robIdx  = new RobPtr
    val canWb   = Bool()
    val cancel  = Bool()
    val valid   = Bool()

    def init: Unit = {
        wbmask := 0.U
        canWb := false.B
        cancel := false.B
        valid := false.B
    }
}

//class WbMergeIdQueue(val size: Int, val allocateWidth: Int, val releaseWidth: Int)(implicit p: Parameters) extends FreeIdxAllocateQueueTemplate(size, allocateWidth, releaseWidth)

class WbMergeStationPtr(size: Int) extends CircularQueuePtr[WbMergeStationPtr](size) with HasCircularQueuePtrHelper
object WbMergeStationPtr {
    def apply(f: Boolean, v: Int, size: Int): WbMergeStationPtr = {
        val ptr = Wire(new WbMergeStationPtr(size))
        ptr.flag    := f.B
        ptr.value   := v.U
        ptr
    }
}
class WbMergeStationPtrHelper(size: Int, allocateWidth: Int, releaseWidth: Int)(implicit p: Parameters) extends XSModule {
    val io = IO(new Bundle {
        val allocate = Vec(allocateWidth, DecoupledIO(new WbMergeStationPtr(size)))
        val release = Flipped(ValidIO(UInt(log2Up(releaseWidth + 1).W)))
        val releasePtrValue = Output(new WbMergeStationPtr(size))
    })

    val allocatePtr = RegInit(WbMergeStationPtr(false, 0, size))
    val releasePtr  = RegInit(WbMergeStationPtr(false, 0, size))

    for((port, i) <- io.allocate.zipWithIndex) {
        val allocPtr = allocatePtr + i.U
        port.bits := allocPtr
        port.valid := (allocPtr >= releasePtr)
    }
    
    val allocatePtrNext = allocatePtr + PopCount(io.allocate.map(_.fire))
    allocatePtr := allocatePtrNext

    val releasePtrNext = releasePtr + io.release.bits
    releasePtr := Mux(io.release.valid, releasePtrNext, releasePtr)

    io.releasePtrValue := releasePtr
}

class WbMergeStation(size: Int = 64, allocateWidth: Int = 4, mergeWidth: Int = 4, wbWidth: Int = 4)(implicit p: Parameters) extends XSModule {
    def bankSize: Int = size / wbWidth

    val io = IO(new Bundle {
        val redirect    = Flipped(ValidIO(new Redirect))
        val waitqueue   = Vec(allocateWidth, DecoupledIO(new WbMergeStationPtr(size)))
        val rob         = Vec(wbWidth, ValidIO(new ExuOutput))
        val exu         = Vec(mergeWidth, Flipped(ValidIO(new ExuOutput)))
        val wbMSFull    = Output(Bool())
    })

    val ptrHelper = Module(new WbMergeStationPtrHelper(size, allocateWidth, wbWidth))

    val mergeTable = RegInit(VecInit(Seq.tabulate(size)(i => {
        val entry = new WbMergeStationEntry
        entry.init
        entry
    })))

    //allocate, connect with WaitQueue
    io.waitqueue <> ptrHelper.io.allocate
    val allocateVec = Vec(allocateWidth, Bool())
    allocateVec := io.waitqueue.map(_.fire)
    for((ptr, en) <- ptrHelper.io.allocate.map(_.bits.value).zip(allocateVec)) {
        when(en) {
            mergeTable(ptr).wbmask := 0.U
            mergeTable(ptr).canWb := false.B
            mergeTable(ptr).cancel := false.B
        }
    }

    //merge, connect with EXU
    for((merge, i) <- io.exu.zipWithIndex) {
        val entry = mergeTable(merge.bits.uop.mergeIdx)
        val mergeId = merge.bits.uop.mergeIdx
        val wbmaskNext = entry.wbmask | merge.bits.wbmask
        when(merge.valid && wbmaskNext.andR) {
            mergeTable(mergeId).canWb := true.B
            mergeTable(mergeId).wbmask := wbmaskNext
        }.elsewhen(merge.valid && (merge.bits.uop.robIdx === entry.robIdx)) {
            mergeTable(mergeId).wbmask := wbmaskNext
        }.elsewhen(merge.valid && (merge.bits.uop.robIdx =/= entry.robIdx)) {
            mergeTable(mergeId).wbmask := wbmaskNext
            mergeTable(mergeId).robIdx := merge.bits.uop.robIdx
        }
    }

    //select writeback, connect with ROB, and release mergeIdx to freeIdxQueue
    val wbVec = Wire(Vec(wbWidth, Bool()))
    
    val wbPtr = Wire(new WbMergeStationPtr(size))
    wbPtr := ptrHelper.io.releasePtrValue

    wbVec(0) := mergeTable(wbPtr.value).canWb
    for(i <- 1 until wbWidth) {
        val entry = mergeTable(wbPtr.value + i.U)
        wbVec(i) := wbVec(i-1) & entry.canWb
    }

    for((port, i) <- io.rob.zipWithIndex) {
        val entry = mergeTable(wbPtr.value + i.U)
        port.bits.uop.robIdx := entry.robIdx
        port.valid := wbVec(i)
    }

    ptrHelper.io.release := PopCount(wbVec)

    //redirect
    

}
