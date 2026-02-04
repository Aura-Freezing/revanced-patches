package app.revanced.patches.protonvpn.premium

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

@Suppress("unused")
val unlockPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlocks premium features and removes server delays.",
) {
    compatibleWith("ch.protonvpn.android")

    execute {
        //
        // 1. VpnUser: fake subscription tier & flags
        //
        run {
            val vpnUserMethod = vpnUserFingerprint.method
            val vpnUserClassType = vpnUserMethod.definingClass
            val vpnUserClass = classes.firstOrNull { it.type == vpnUserClassType } ?: return@run

            // Find the tier field by looking for a field accessed by methods that look like tier checks.
            val fieldScores = mutableMapOf<FieldReference, Int>()

            vpnUserClass.methods.forEach { method ->
                val instructions = method.implementation?.instructions ?: return@forEach

                // Identify if this method looks like a tier check
                var score = 0
                if (method.returnType == "Z") {
                    val hasConst1 = instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == 1 }
                    val hasConst2 = instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == 2 }
                    val hasConst3 = instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == 3 }
                    val hasIfZero = instructions.any { it.opcode == Opcode.IF_EQZ || it.opcode == Opcode.IF_NEZ }

                    if (hasConst1) score++
                    if (hasConst2) score++
                    if (hasConst3) score++
                    if (hasIfZero) score++
                } else if (method.returnType == "I") {
                    // userTier() might just return the field, so it might not have constants.
                    score += 1
                }

                if (score > 0) {
                    // Find fields read in this method
                    instructions.forEach { insn ->
                        if (insn.opcode == Opcode.IGET || insn.opcode == Opcode.IGET_OBJECT) {
                            val ref = (insn as? ReferenceInstruction)?.reference as? FieldReference
                            if (ref != null && ref.definingClass == vpnUserClassType) {
                                // Check type: should be Int or Integer
                                if (ref.type == "I" || ref.type == "Ljava/lang/Integer;") {
                                    fieldScores[ref] = fieldScores.getOrDefault(ref, 0) + score
                                }
                            }
                        }
                    }
                }
            }

            // Pick the field with the highest score
            val maxTierField = fieldScores.entries.maxByOrNull { it.value }?.key ?: return@run

            vpnUserClass.methods.forEach { method ->
                // Does this method read the maxTier field?
                val instructions = method.implementation?.instructions ?: return@forEach

                val readsMaxTier = instructions.any { insn ->
                    (insn.opcode == Opcode.IGET || insn.opcode == Opcode.IGET_OBJECT) &&
                            (insn as? ReferenceInstruction)?.reference == maxTierField
                }

                if (!readsMaxTier) return@forEach

                // Boolean-returning methods (isFreeUser, isPlus, etc.)
                if (method.returnType == "Z") {
                    var hasConst1 = false
                    var hasConst2 = false
                    var hasConst3 = false
                    var hasIfZero = false // IF_EQZ / IF_NEZ

                    for (inst in instructions) {
                        if (inst is NarrowLiteralInstruction) {
                            when (inst.narrowLiteral) {
                                1 -> hasConst1 = true
                                2 -> hasConst2 = true
                                3 -> hasConst3 = true
                            }
                        } else if (inst.opcode == Opcode.IF_EQZ || inst.opcode == Opcode.IF_NEZ) {
                            hasIfZero = true
                        }
                    }

                    // Same heuristics you wrote originally:
                    when {
                        hasConst3 -> {
                            // isPMTeam (checks 3) -> force false
                            method.returnEarly(0)
                        }
                        hasConst2 -> {
                            // isPlus (checks 2) -> force true
                            method.returnEarly(1)
                        }
                        hasIfZero -> {
                            // isFreeUser (checks 0 using if-eqz) -> force false
                            method.returnEarly(0)
                        }
                        hasConst1 -> {
                            // isBasic (checks 1) -> force true
                            method.returnEarly(1)
                        }
                    }
                }
                // Int-returning method: userTier()
                else if (method.returnType == "I") {
                    // userTier (0 = free, 1 = basic, 2 = plus, 3 = team ?)
                    // Force PLUS (2)
                    method.returnEarly(2)
                }
            }
        }

        //
        // 2. AppConfigResponse: remove server change delays
        //
        run {
            val appConfigMethod = appConfigResponseFingerprint.method
            val appConfigClassType = appConfigMethod.definingClass
            val appConfigClass = classes.firstOrNull { it.type == appConfigClassType } ?: return@run

            appConfigClass.methods.forEach { method ->
                if (method.returnType != "I") return@forEach

                val instructions = method.implementation?.instructions ?: return@forEach

                val uses90 = instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == 90 }
                val uses1200 = instructions.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == 1200 }

                if (uses90 || uses1200) {
                    // Force delay = 0
                    method.returnEarly(0)
                }
            }
        }
    }
}
