// app/revanced/patches/protonvpn/premium/UnlockPremiumPatch.kt
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

            val integerType = "Ljava/lang/Integer;"
            val maxTierField = vpnUserClass.fields.find { it.type == integerType } ?: return@run

            vpnUserClass.methods.forEach { method ->
                // Does this method read the maxTier field?
                val readsMaxTier = method.implementation
                    ?.instructions
                    ?.any { insn ->
                        insn.opcode == Opcode.IGET_OBJECT &&
                            (insn as? ReferenceInstruction)?.reference
                                ?.let { it as? FieldReference }?.name == maxTierField.name
                    } == true

                if (!readsMaxTier) return@forEach

                // Boolean-returning methods (isFreeUser, isPlus, etc.)
                if (method.returnType == "Z") {
                    var hasConst1 = false
                    var hasConst2 = false
                    var hasConst3 = false
                    var hasIfZero = false // IF_EQZ / IF_NEZ

                    val instructions = method.implementation?.instructions ?: return@forEach

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
                            method.returnEarly(false)
                        }
                        hasConst2 -> {
                            // isPlus (checks 2) -> force true
                            method.returnEarly(true)
                        }
                        hasIfZero -> {
                            // isFreeUser (checks 0 using if-eqz) -> force false
                            method.returnEarly(false)
                        }
                        hasConst1 -> {
                            // isBasic (checks 1) -> force true
                            method.returnEarly(true)
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
