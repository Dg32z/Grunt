package net.spartanb312.grunt.process.transformers.encrypt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.spartanb312.genesis.kotlin.extensions.*
import net.spartanb312.genesis.kotlin.extensions.insn.*
import net.spartanb312.genesis.kotlin.method
import net.spartanb312.grunt.config.Configs
import net.spartanb312.grunt.config.setting
import net.spartanb312.grunt.process.MethodProcessor
import net.spartanb312.grunt.process.Transformer
import net.spartanb312.grunt.process.resource.ResourceCache
import net.spartanb312.grunt.process.transformers.rename.ReflectionSupportTransformer
import net.spartanb312.grunt.utils.count
import net.spartanb312.grunt.utils.extensions.isAbstract
import net.spartanb312.grunt.utils.extensions.isInterface
import net.spartanb312.grunt.utils.getRandomString
import net.spartanb312.grunt.utils.logging.Logger
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import kotlin.random.Random

/**
 * Encrypt strings
 * Last update on 2024/10/23
 */
object StringEncryptTransformer : Transformer("StringEncrypt", Category.Encryption), MethodProcessor {

    private val times by setting("Intensity", 1)
    private val exclusion by setting("Exclusion", listOf())

    private val String.reflectionExcluded
        get() = ReflectionSupportTransformer.enabled && ReflectionSupportTransformer.strBlacklist.contains(this)

    override fun ResourceCache.transform() {
        Logger.info(" - Encrypting strings...")
        val count = count {
            repeat(times) { t ->
                if (times > 1) Logger.info("    Encrypting strings ${t + 1} of $times times")
                runBlocking {
                    nonExcluded.asSequence()
                        .filter { c ->
                            !c.isInterface && c.version > Opcodes.V1_5
                                    && exclusion.none { c.name.startsWith(it) }
                        }.forEach { classNode ->
                            fun job() {
                                val decryptMethodName = getRandomString(10)
                                val key = Random.nextInt(0x8, 0x800)
                                var shouldAdd = false
                                for (methodNode in classNode.methods) {
                                    if (methodNode.isAbstract) continue
                                    methodNode.instructions.asSequence()
                                        .filter { (it is LdcInsnNode && it.cst is String && !(it.cst as String).reflectionExcluded) }
                                        .forEach { insnNode ->
                                            methodNode.instructions.insert(
                                                insnNode,
                                                MethodInsnNode(
                                                    Opcodes.INVOKESTATIC, classNode.name,
                                                    decryptMethodName, "(Ljava/lang/String;)Ljava/lang/String;",
                                                    false
                                                )
                                            )
                                            (insnNode as LdcInsnNode).cst = encrypt(insnNode.cst as String, key)
                                            if (t == 0) add()
                                            shouldAdd = true
                                        }
                                }
                                if (shouldAdd) classNode.methods.add(createDecryptMethod(decryptMethodName, key))
                            }
                            if (Configs.Settings.parallel) launch(Dispatchers.Default) { job() } else job()
                        }
                }
            }
        }.get()
        Logger.info("    Encrypted $count strings")
    }

    override fun transformMethod(owner: ClassNode, method: MethodNode) {
        val decryptMethodName = getRandomString(10)
        val key = Random.nextInt(0x8, 0x800)
        var shouldAdd = false
        method.instructions.asSequence()
            .filter { (it is LdcInsnNode && it.cst is String) }
            .forEach { insnNode ->
                method.instructions.insert(
                    insnNode,
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        owner.name,
                        decryptMethodName, "(Ljava/lang/String;)Ljava/lang/String;",
                        false
                    )
                )
                (insnNode as LdcInsnNode).cst = encrypt(insnNode.cst as String, key)
                shouldAdd = true
            }
        if (shouldAdd) owner.methods.add(createDecryptMethod(decryptMethodName, key))
    }

    fun createDecryptMethod(methodName: String, key: Int): MethodNode = method(
        PRIVATE + STATIC + SYNTHETIC + BRIDGE,
        methodName,
        "(Ljava/lang/String;)Ljava/lang/String;"
    ) {
        INSTRUCTIONS {
            //A:
            NEW("java/lang/StringBuilder")
            DUP
            INVOKESPECIAL("java/lang/StringBuilder", "<init>", "()V")
            ASTORE(1)
            ICONST_0
            ISTORE(2)
            GOTO(L["labelC"])

            //B:
            LABEL(L["labelB"])
            FRAME(Opcodes.F_APPEND, 2, arrayOf("java/lang/StringBuilder", Opcodes.INTEGER), 0)
            ALOAD(1)
            ALOAD(0)
            ILOAD(2)
            INVOKEVIRTUAL("java/lang/String", "charAt", "(I)C")
            LDC(key)
            IXOR
            I2C
            INVOKEVIRTUAL("java/lang/StringBuilder", "append", "(C)Ljava/lang/StringBuilder;")
            POP
            IINC(2, 1)

            //C:
            LABEL(L["labelC"])
            FRAME(Opcodes.F_SAME, 0, null, 0)
            ILOAD(2)
            ALOAD(0)
            INVOKEVIRTUAL("java/lang/String", "length", "()I")
            IF_ICMPLT(L["labelB"])
            ALOAD(1)
            INVOKEVIRTUAL("java/lang/StringBuilder", "toString", "()Ljava/lang/String;")
            ARETURN
        }
        MAXS(3, 3)
    }

    fun encrypt(string: String, xor: Int): String {
        val stringBuilder = StringBuilder()
        for (element in string) {
            stringBuilder.append((element.code xor xor).toChar())
        }
        return stringBuilder.toString()
    }

}