/*                                                                           *\
**        _____ ____  _____   _____    __                                    **
**       / ___// __ \/  _/ | / /   |  / /   Crypto                           **
**       \__ \/ /_/ // //  |/ / /| | / /    (c) Dolu, All rights reserved    **
**      ___/ / ____// // /|  / ___ |/ /___                                   **
**     /____/_/   /___/_/ |_/_/  |_/_____/                                   **
**                                                                           **
**      This library is free software; you can redistribute it and/or        **
**    modify it under the terms of the GNU Lesser General Public             **
**    License as published by the Free Software Foundation; either           **
**    version 3.0 of the License, or (at your option) any later version.     **
**                                                                           **
**      This library is distributed in the hope that it will be useful,      **
**    but WITHOUT ANY WARRANTY; without even the implied warranty of         **
**    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU      **
**    Lesser General Public License for more details.                        **
**                                                                           **
**      You should have received a copy of the GNU Lesser General Public     **
**    License along with this library.                                       **
\*                                                                           */
package spinal.crypto.symmetric.aes

import spinal.core._
import spinal.lib._
import spinal.lib.fsm.{EntryPoint, State, StateMachine}

import spinal.crypto.symmetric.{SymmetricCryptoBlockGeneric, SymmetricCryptoBlockIO}

/**
  *
  * Advanced Encryption Standard (AES)
  *
  * This design works in encrypt or decrypt with 128, 192 and 256 key width
  *
  *****************************************************************************
  *
  * Encryption :
  *              PlaintText
  *                   |
  *           | Key addition  <---------- k0
  *                   |
  *           | Byte substitution
  *   round   | Shift row
  *     n     | MixColumn
  *           | Key addition  <---------- kn
  *                   |
  *           | Byte substitution
  *   last    | Shift row
  *   round   | Key addition  <---------- klast
  *                   |
  *               CipherText
  *
  *
  * Decryption :
  *               CipherText
  *                   |
  *            | Key addition  <---------- klast
  *    round   | Inv shift row
  *      0     | Inv byte substitution
  *                   |
  *            | Key addition  <---------- kn
  *    round   | Inv mixColumn
  *      n     | Inv shif row
  *            | Inv byte substitution
  *                   |
  *            | Key addition  <---------- k0
  *                   |
  *               Plaintext
  *
  */
class AESCore_Std(keyWidth: BitCount) extends Component{

  assert(List(128, 192, 256).contains(keyWidth.value), "AES support only 128/192/256 keys width")


  val gIO  = SymmetricCryptoBlockGeneric(keyWidth   = keyWidth,
    blockWidth  = AESCoreSpec.blockWidth,
    useEncDec   = true)

  val io = slave(new SymmetricCryptoBlockIO(gIO))

  val sBoxMem    = Mem(Bits(8 bits), AESCoreSpec.sBox.map(B(_, 8 bits)))
  val sBoxMemInv = Mem(Bits(8 bits), AESCoreSpec.sBoxInverse.map(B(_, 8 bits)))
  val dataState  = Reg(Vec(Bits(8 bits), 16))
  val cntRound   = Reg(UInt(log2Up(AESCoreSpec.nbrRound(keyWidth)) bits))
  val nbrRound   = AESCoreSpec.nbrRound(keyWidth)

  /* Key scheduling */
  val keySchedule = new KeyScheduleCore128_Std()
  keySchedule.io.init.valid   := False
  keySchedule.io.update.valid := False
  keySchedule.io.round        := (cntRound + 1).resized
  keySchedule.io.init.key     := io.cmd.key

  /* Output default value */
  io.cmd.ready := False
  io.rsp.valid := False
  io.rsp.block := dataState.asBits

  val blockByte = io.cmd.block.subdivideIn(8 bits).reverse
  val keyByte   = keySchedule.io.rsp.key_i.subdivideIn(8 bits).reverse


  /**
    * Main state machine
    */
  val sm = new StateMachine{

    val keyAddition_cmd = Stream(NoData)
    keyAddition_cmd.valid := False

    val byteSub_cmd = Stream(NoData)
    byteSub_cmd.valid := False

    val shiftRow_cmd = Stream(NoData)
    shiftRow_cmd.valid := False

    val mixCol_cmd = Stream(NoData)
    mixCol_cmd.valid := False

    val sIdle: State = new State with EntryPoint{
      whenIsActive{
        when(io.cmd.valid){
          cntRound := io.cmd.enc ? U(0) | U(15)
          keySchedule.io.init.valid := True
        }
        when(keySchedule.io.init.ready){
          goto(sKeyAdd)
        }
      }
    }

    val sKeyAdd: State = new State{
      whenIsActive{
    //    when(keySchedule.io.rsp.valid){
          keyAddition_cmd.valid := True
    //    }
        when(keyAddition_cmd.ready){
          keySchedule.io.update.valid := True
          when(io.cmd.enc){
            when(cntRound === nbrRound){
              io.cmd.ready := True
              io.rsp.valid := True
              goto(sIdle)
            }otherwise {
              goto(sByteSub)
            }
          }otherwise{
            when(cntRound === nbrRound-1){
              goto(sShiftRow)
            }.elsewhen(cntRound === 0){
              io.cmd.ready := True
              goto(sIdle)
            }otherwise{
              goto(sMixColumn)
            }
          }
        }
      }
    }

    val sByteSub: State = new State{
      whenIsActive{
        byteSub_cmd.valid := True
        when(byteSub_cmd.ready){
          when(io.cmd.enc){
            cntRound := cntRound + 1
            goto(sShiftRow)
          }otherwise{
            cntRound := cntRound - 1
            goto(sKeyAdd)
          }
        }
      }
    }

    val sShiftRow: State = new State{
      whenIsActive{
        shiftRow_cmd.valid := True
        when(shiftRow_cmd.ready){
          when(io.cmd.enc){
            when(cntRound === nbrRound){
              goto(sKeyAdd)
            }otherwise{
              goto(sMixColumn)
            }
          }otherwise{
            goto(sByteSub)
          }
        }
      }
    }

    val sMixColumn: State = new State{
      whenIsActive{
        mixCol_cmd.valid := True
        when(mixCol_cmd.ready){
          when(io.cmd.enc){
            goto(sKeyAdd)
          }otherwise{
            goto(sShiftRow)
          }
        }
      }
    }
  }


  /**
    * Key Addition
    * newState = currentState XOR key
    */
  val keyAddition = new Area{
    sm.keyAddition_cmd.ready := False

    when(sm.keyAddition_cmd.valid){
      when(cntRound === 0){
        for(i <- 0 until dataState.length){
          dataState(i) := blockByte(i) ^ keyByte(i)
        }
      }otherwise{
        for(i <- 0 until dataState.length){
          dataState(i) := dataState(i) ^ keyByte(i)
        }
      }

      sm.keyAddition_cmd.ready := True
    }
  }


  /**
    * Byte substitution
    * 16 identical SBOX
    * newState(i) = SBox(currentState(i))
    */
  val byteSubstitution = new Area {

    val cntByte = Counter(16)
    sm.byteSub_cmd.ready := cntByte.willOverflowIfInc

    when(sm.byteSub_cmd.valid) {
      cntByte.increment()

      when(io.cmd.enc){
        dataState(cntByte) := sBoxMem(dataState(cntByte).asUInt)
      }otherwise{
        dataState(cntByte) := sBoxMemInv(dataState(cntByte).asUInt)
      }

    }.otherwise{
      cntByte.clear()
    }
  }


  /**
    * Shift row
    * newState(i) = ShiftRow(currentState(i))
    */
  val shiftRow = new Area{
    sm.shiftRow_cmd.ready := False

    when(sm.shiftRow_cmd.valid) {
      when(io.cmd.enc){
        for ((src, dst) <- AESCoreSpec.shiftRowIndex.zipWithIndex){
          dataState(dst) := dataState(src)
        }
      }otherwise{
        for ((src, dst) <- AESCoreSpec.invShiftRowIndex.zipWithIndex){
          dataState(dst) := dataState(src)
        }
      }
      sm.shiftRow_cmd.ready := True
    }
  }


  /**
    * Mix Column
    *
    * newState(i) = MixColumn(currentState(i))
    *
    * Encryption :
    *
    *   C0 = 02 * B0 XOR 03 * B1 XOR 01 * B2 XOR 01 * B3
    *   C1 = 01 * B0 XOR 02 * B1 XOR 03 * B2 XOR 01 * B3
    *   C2 = 01 * B0 XOR 01 * B1 XOR 02 * B2 XOR 03 * B3
    *   C3 = 03 * B0 XOR 01 * B1 XOR 01 * B2 XOR 02 * B3
    *
    * Decryption :
    *
    *   C0 = 0E * B0 XOR 0B * B1 XOR 0D * B2 XOR 09 * B3
    *   C1 = 09 * B0 XOR 0E * B1 XOR 0B * B2 XOR 0D * B3
    *   C2 = 0D * B0 XOR 09 * B1 XOR 0E * B2 XOR 0B * B3
    *   C3 = 0B * B0 XOR 0D * B1 XOR 09 * B2 XOR 0E * B3
    */
  val mixColumn = new Area{

    val cntColumn = Reg(UInt(log2Up(16) bits))
    sm.mixCol_cmd.ready := cntColumn === 3*4

    when(sm.mixCol_cmd.valid){

      when(io.cmd.enc){
        dataState(0 + cntColumn) := mult_02(dataState(0 + cntColumn)) ^ mult_03(dataState(1 + cntColumn)) ^ mult_01(dataState(2 + cntColumn)) ^ mult_01(dataState(3 + cntColumn))
        dataState(1 + cntColumn) := mult_01(dataState(0 + cntColumn)) ^ mult_02(dataState(1 + cntColumn)) ^ mult_03(dataState(2 + cntColumn)) ^ mult_01(dataState(3 + cntColumn))
        dataState(2 + cntColumn) := mult_01(dataState(0 + cntColumn)) ^ mult_01(dataState(1 + cntColumn)) ^ mult_02(dataState(2 + cntColumn)) ^ mult_03(dataState(3 + cntColumn))
        dataState(3 + cntColumn) := mult_03(dataState(0 + cntColumn)) ^ mult_01(dataState(1 + cntColumn)) ^ mult_01(dataState(2 + cntColumn)) ^ mult_02(dataState(3 + cntColumn))
      }otherwise{
        dataState(0 + cntColumn) := dataState(0 + cntColumn) ^ dataState(1 + cntColumn) ^ dataState(2 + cntColumn) ^ dataState(3 + cntColumn)
        dataState(1 + cntColumn) := dataState(0 + cntColumn) ^ dataState(1 + cntColumn) ^ dataState(2 + cntColumn) ^ dataState(3 + cntColumn)
        dataState(2 + cntColumn) := dataState(0 + cntColumn) ^ dataState(1 + cntColumn) ^ dataState(2 + cntColumn) ^ dataState(3 + cntColumn)
        dataState(3 + cntColumn) := dataState(0 + cntColumn) ^ dataState(1 + cntColumn) ^ dataState(2 + cntColumn) ^ dataState(3 + cntColumn)
      }

      cntColumn := cntColumn + 4

    }otherwise{
      cntColumn := 0
    }

    def mult_03(din: Bits): Bits = (din(7) ^ din(6)) ## (din(5) ^ din(6)) ## (din(5) ^ din(4)) ## (din(3) ^ din(4) ^ din(7)) ## (din(2) ^ din(3) ^ din(7))  ## (din(2) ^ din(1)) ## (din(1) ^ din(7) ^ din(0)) ## (din(7) ^ din(0))
    def mult_02(din: Bits): Bits = din(6) ## din(5) ## din(4) ## (din(3) ^ din(7)) ## (din(2) ^ din(7)) ## din(1) ## (din(0) ^ din(7)) ## din(7)
    def mult_01(din: Bits): Bits = din

  }
}





