package app.revanced.patches.protonvpn.telemetry

import app.revanced.patcher.extensions.InstructionExtensions.replaceInstruction
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.util.returnEarly
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction21c
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction22c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

@Suppress("unused")
val disableTelemetryPatch = bytecodePatch(
    name = "Disable telemetry",
    description = "Disables telemetry and crash reporting.",
) {
    compatibleWith("ch.protonvpn.android")

    execute {
        //
        // 1. Disable Sentry integration: kill all methods in the matched class.
        //
        run {
            // This will throw a PatchException if the fingerprint doesn’t match – that’s fine.
            val sentryMethod = sentryIntegrationFingerprint.method
            val sentryClassType = sentryMethod.definingClass

            // Find the MutableClass for that type
            val sentryClass = classes.firstOrNull { it.type == sentryClassType } ?: return@run

            sentryClass.methods.forEach { method ->
                when (method.returnType) {
                    "Z" -> method.returnEarly(0)     // boolean → always “disabled”
                    "V" -> method.returnEarly()      // void → no‑op
                }
            }
        }

        //
        // 2. Find the telemetry boolean field in LocalUserSettings via "Telemetry: " usage
        //    and force all reads of that field in the whole app to return false.
        //
        run {
            val settingsMethod = localUserSettingsFingerprint.method
            val settingsClassType = settingsMethod.definingClass
            val settingsClass = classes.firstOrNull { it.type == settingsClassType } ?: return@run

            var telemetryField: FieldReference? = null

            // Find the field by scanning for "Telemetry: " then the following IGET_BOOLEAN or SGET_BOOLEAN
            for (method in settingsClass.methods) {
                if (telemetryField != null) break

                val impl = method.implementation ?: continue
                val instructions = impl.instructions.toList()

                for (i in instructions.indices) {
                    val inst = instructions[i]

                    if (
                        inst.opcode == Opcode.CONST_STRING &&
                        (inst as? ReferenceInstruction)?.reference
                            ?.let { it as? StringReference }?.string == "Telemetry: "
                    ) {
                        // Look ahead a few instructions for IGET_BOOLEAN or SGET_BOOLEAN
                        for (j in 1..15) {
                            val idx = i + j
                            if (idx >= instructions.size) break
                            val nextInst = instructions[idx]

                            if (nextInst.opcode == Opcode.IGET_BOOLEAN || nextInst.opcode == Opcode.SGET_BOOLEAN) {
                                telemetryField =
                                    (nextInst as ReferenceInstruction).reference as FieldReference
                                break
                            }
                        }
                    }

                    if (telemetryField != null) break
                }
            }

            val targetField = telemetryField ?: return@run

            // Replace all reads of this field with const/4 vX, 0x0
            classes.forEach { classDef ->
                classDef.methods.forEach { method ->
                    val impl = method.implementation ?: return@forEach
                    val instructions = impl.instructions

                    val indicesToPatch = ArrayList<Int>()

                    instructions.forEachIndexed { index, inst ->
                        if (inst.opcode == Opcode.IGET_BOOLEAN || inst.opcode == Opcode.SGET_BOOLEAN) {
                            val ref = (inst as ReferenceInstruction).reference as FieldReference

                            if (ref.name == targetField.name &&
                                ref.definingClass == targetField.definingClass &&
                                ref.type == targetField.type
                            ) {
                                indicesToPatch.add(index)
                            }
                        }
                    }

                    // Patch from the end so indexes stay valid
                    for (index in indicesToPatch.asReversed()) {
                        val inst = instructions[index]
                        val register: Int = when (inst) {
                            is Instruction22c -> inst.registerA
                            is Instruction21c -> inst.registerA
                            else -> continue // Should not happen
                        }

                        method.replaceInstruction(
                            index,
                            "const/4 v$register, 0x0"
                        )
                    }
                }
            }
        }
    }
}
