package app.revanced.patches.protonvpn.telemetry

import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables telemetry and crash reporting.",
) {
    compatibleWith("ch.protonvpn.android")

    execute {
        // 1. Disable Sentry Integration
        sentryIntegrationFingerprint.result?.let { result ->
            result.classDef.methods.forEach { method ->
                 if (method.returnType == "Z") {
                     method.returnEarly(0)
                 } else if (method.returnType == "V") {
                     method.returnEarly()
                 }
            }
        }

        // 2. Disable Telemetry Field
        localUserSettingsFingerprint.result?.let { result ->
            val settingsClass = result.classDef
            var telemetryField: FieldReference? = null
            
            // Find the telemetry field by looking for "Telemetry: " string usage
            for (method in settingsClass.methods) {
                if (telemetryField != null) break
                
                val instructionIterable = method.implementation?.instructions ?: continue
                val instructions = instructionIterable.toList()
                
                for (i in instructions.indices) {
                    val instruction = instructions[i]
                    if (instruction.opcode == Opcode.CONST_STRING && 
                        (instruction as? ReferenceInstruction)?.reference?.let { (it as? StringReference)?.string } == "Telemetry: ") {
                        
                        // Scan ahead for iget-boolean
                        for (j in 1..15) {
                            if (i + j >= instructions.size) break
                            val nextInst = instructions[i + j]
                            if (nextInst.opcode == Opcode.IGET_BOOLEAN) {
                                telemetryField = (nextInst as ReferenceInstruction).reference as FieldReference
                                break
                            }
                        }
                    }
                    if (telemetryField != null) break
                }
            }
            
            if (telemetryField != null) {
                val targetField = telemetryField!!
                // Replace ALL reads of this field in the entire app with const/4 0
                classes.forEach { classDef ->
                    classDef.methods.forEach { method ->
                        val implementation = method.implementation ?: return@forEach
                        // Use mutableInstructions to modify
                        val instructions = implementation.mutableInstructions
                        
                        // Find indices to patch
                        val indicesToPatch = ArrayList<Int>()
                        for (i in instructions.indices) {
                            val inst = instructions[i]
                            if (inst.opcode == Opcode.IGET_BOOLEAN) {
                                val ref = (inst as ReferenceInstruction).reference as FieldReference
                                if (ref.name == targetField.name && 
                                    ref.definingClass == targetField.definingClass && 
                                    ref.type == targetField.type) {
                                    indicesToPatch.add(i)
                                }
                            }
                        }
                        
                        // Apply patches in reverse order
                        indicesToPatch.asReversed().forEach { index ->
                             val inst = instructions[index] as Instruction22c
                             val register = inst.registerA
                             replaceInstruction(method, index, "const/4 v$register, 0x0")
                        }
                    }
                }
            }
        }
    }
}
