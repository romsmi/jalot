package agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.bytecode.MethodInfo;

public class Agent implements ClassFileTransformer {

    public static void premain(String agentArgument, Instrumentation instrumentation) {
        instrumentation.addTransformer(new Agent());
        System.out.println("Start instrumented processing");
    }

    /**
     * @see ClassFileTransformer
     */
    @SuppressWarnings("rawtypes")
    public byte[] transform(ClassLoader loader, String className, Class clazz, ProtectionDomain domain, byte[] bytes) {
        if (className.startsWith("java")) {
            return bytes;
        }
        byte[] transformedClass = transformClass(className, clazz, bytes);
        return transformedClass;
    }

    @SuppressWarnings("rawtypes")
    private byte[] transformClass(String name, Class clazz, byte[] b) {
        ClassPool pool = ClassPool.getDefault();

        CtClass ctClass = null;
        try {
            ctClass = pool.makeClass(new java.io.ByteArrayInputStream(b));
            if (!ctClass.isInterface()) {
                CtBehavior[] ctMethods = ctClass.getDeclaredBehaviors();
                for (int i = 0; i < ctMethods.length; i++) {
                    if (!ctMethods[i].isEmpty()) {
                        transformMethod(ctClass, ctMethods[i]);
                    }
                }
                b = ctClass.toBytecode();
            }
        }
        catch (Exception e) {
            e.printStackTrace(System.err);
        }
        finally {
            if (ctClass != null) {
                ctClass.detach();
            }
        }
        return b;
    }

//    private byte[] transformClass(String name, Class clazz, byte[] b) throws Exception {
//        ClassPool pool = ClassPool.getDefault();
//        CtClass ctClass = pool.makeClass(new java.io.ByteArrayInputStream(b));
//        CtBehavior[] ctMethods = ctClass.getDeclaredBehaviors();
//        for (int i = 0; i < ctMethods.length; i++) {
//            transformMethod(ctClass, ctMethods[i]);
//        }
//        b = ctClass.toBytecode();
//        return b;
//    }

    private void transformMethod(CtClass ctClass, CtBehavior method) throws NotFoundException, CannotCompileException {

        String methodName = method.getMethodInfo().getName();
        String before = "System.out.println(\"-> " + methodName + "\" + \" enter\");";
        String after = "";

        before = sumBefore(methodName, before);
        after = sumAfter(methodName, after);
        after += "System.out.println(\"<- " + methodName + "\" + \" leave\");";

        method.insertBefore(before);
        method.insertAfter(after);
    }

    private String sumBefore(String methodName, String before) {
        if ("sum".equals(methodName)) {
            before += "System.out.println(\"   \"+$1);" + "System.out.println(\"   \"+$2);";
        }
        return before;
    }

    private String sumAfter(String methodName, String after) {
        if ("sum".equals(methodName)) {
            after += "StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();"
                + "StackTraceElement stackTraceElement = stackTraceElements[1];"
                + "System.out.println(\"   caller: \" + stackTraceElement.getClassName());";
        }
        return after;
    }
}