package app.revanced.patches.protonvpn.premium

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

val unlockPremiumPatch = bytecodePatch(
    name = "Unlock Premium",
    description = "Unlocks premium features and removes server delays.",
) {
    compatibleWith("ch.protonvpn.android")

    execute {
        // VpnUser patches
        vpnUserFingerprint.result?.let { result ->
            val vpnUserClass = result.classDef
            val integerType = "Ljava/lang/Integer;"
            val maxTierField = vpnUserClass.fields.find { it.type == integerType }
            
            if (maxTierField != null) {
                vpnUserClass.methods.forEach { method ->
                    val readsMaxTier = method.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.IGET_OBJECT &&
                        (instruction as? ReferenceInstruction)?.reference?.let { ref ->
                             (ref as? FieldReference)?.name == maxTierField.name
                        } == true
                    } == true
                    
                    if (readsMaxTier) {
                        if (method.returnType == "Z") { // Boolean
                            var hasConst1 = false
                            var hasConst2 = false
                            var hasConst3 = false
                            var hasIfZero = false // if-eqz or if-nez
                            
                            val instructions = method.implementation?.instructions
                            if (instructions != null) {
                                for (inst in instructions) {
                                    if (inst is NarrowLiteralInstruction) {
                                        val value = inst.narrowLiteral
                                        if (value == 1) hasConst1 = true
                                        else if (value == 2) hasConst2 = true
                                        else if (value == 3) hasConst3 = true
                                    } else if (inst.opcode == Opcode.IF_EQZ || inst.opcode == Opcode.IF_NEZ) {
                                        hasIfZero = true
                                    }
                                }
                            }
                            
                            // Heuristics to determine method type based on constants used
                            if (hasConst3) {
                                // isPMTeam (checks 3) -> False
                                method.returnEarly(0)
                            } else if (hasConst2) {
                                // isPlus (checks 2) -> True
                                method.returnEarly(1)
                            } else if (hasIfZero) {
                                // isFreeUser (checks 0 using if-eqz) -> False
                                method.returnEarly(0)
                            } else if (hasConst1) {
                                // isBasic (checks 1) -> True
                                // Check this last because isFreeUser also has const 1 (for return true)
                                // but isFreeUser is caught by hasIfZero
                                method.returnEarly(1)
                            }
                            
                        } else if (method.returnType == "I") { // Int
                             // userTier (returns int)
                             method.returnEarly(2)
                        }
                    }
                }
            }
        }
        
        // AppConfigResponse patches
        appConfigResponseFingerprint.result?.let { result ->
            val appConfigClass = result.classDef
            appConfigClass.methods.forEach { method ->
                if (method.returnType == "I") {
                    val uses90 = method.implementation?.instructions?.any { instruction ->
                         (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 90
                    } == true
                    val uses1200 = method.implementation?.instructions?.any { instruction ->
                         (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 1200
                    } == true
                    if (uses90 || uses1200) {
                        method.returnEarly(0)
                    }
                }
            }
        }
    }
}
