package agent;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Logger;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.AccessFlag;

public class Agent implements ClassFileTransformer
{
 
    private static final boolean MAIN = false;
    private static final boolean PUBLIC = false;
    
    
    private static final String LOG_LEVEL_DEBUG = "debug";
    private final static String QUOTE = "\"";

    private static final String LOG_LEVEL = LOG_LEVEL_DEBUG;

    private static ArrayList<String> blackList;
    private static ArrayList<String> whiteList;
    
    static {
        
       
        
        List<String> linesBlack = null;
        List<String> linesWhite = null;
        
        Path pathBlack = Paths.get("./tracy.blacklist");
        Path pathWhite = Paths.get("./tracy.whitelist");
        try
        {
            linesBlack = Files.readAllLines(pathBlack, Charset.defaultCharset());
            linesWhite = Files.readAllLines(pathWhite, Charset.defaultCharset());
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        
        blackList = new ArrayList<String>(linesBlack != null ? linesBlack.size() : 1);
        for (String line : linesBlack)
        {
            blackList.add(line);
        }

        whiteList = new ArrayList<String>(linesWhite != null ? linesWhite.size() : 1);
        for (String line : linesWhite)
        {
            whiteList.add(line);
        }
    }
    
    public static void premain(String agentArgument, Instrumentation instrumentation)
    {
        instrumentation.addTransformer(new Agent());
        System.out.println("=====================================");
        System.out.println("### Start instrumented processing ###");
        System.out.println("=====================================");
        System.out.println("");
    }

    /**
     * @see ClassFileTransformer
     */
    @SuppressWarnings("rawtypes")
    public byte[] transform(ClassLoader loader, String clazzName, Class clazz, ProtectionDomain domain, byte[] bytes)
    {
        if (clazzName.startsWith("java") || clazzName.startsWith("sun/") || clazzName.startsWith("com/sun/"))
        {
            return bytes;
        }
        byte[] transformedClass = transformClass(clazzName, clazz, bytes);
        return transformedClass;
    }

    @SuppressWarnings({ "rawtypes" })
    private byte[] transformClass(String clazzName, Class clazz, byte[] bytes)
    {
        ClassPool pool = ClassPool.getDefault();

        pool.importPackage("org.apache.logging.log4j");
        pool.importPackage("com.jamonapi");
        

        //--- Start filtering
        filterClasses: {
            for (String entry : blackList)
            {
                if (clazzName.startsWith(entry)) return bytes;        
            }
            
            boolean whiteEntryFound = whiteList.isEmpty();
            for (String entry : whiteList)
            {
                if (clazzName.startsWith(entry)) {
                    whiteEntryFound = true;
                    break;
                };        
            }
            if (!whiteEntryFound) return bytes;
        }
        //--- End filtering

        System.out.println(String.format("### Instrument %s now ###", clazzName));

        CtClass ctClass = null;
        try
        {
            ctClass = pool.makeClass(new java.io.ByteArrayInputStream(bytes));
            if (!ctClass.isInterface())
            {
                try
                {
//                    CtClass cc = pool.get(className);
                    CtClass cc = ctClass;
                    CtClass ctLogger = pool.get(Logger.class.getCanonicalName());
                    CtField f = new CtField(ctLogger, "_logger", cc);
                    f.setModifiers(Modifier.PRIVATE);
                    f.setModifiers(Modifier.STATIC);
                    cc.addField(f);
//                     cc.writeFile("C:\\temp");
                }
                catch (NotFoundException | CannotCompileException e)
                {
                    e.printStackTrace();
                }
                
                CtMethod[] ctMethods = ctClass.getDeclaredMethods();
                for (int i = 0; i < ctMethods.length; i++)
                {
                    if (!ctMethods[i].isEmpty())
                    {
                        transformMethod(ctClass, ctMethods[i]);
                    }
                }
                bytes = ctClass.toBytecode();
                
            }
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
        finally
        {
            if (ctClass != null)
            {
                ctClass.detach();
            }
        }
        return bytes;
    }

    private void transformMethod(CtClass ctClass, CtMethod method) throws NotFoundException, CannotCompileException
    {
        
        String methodName = method.getMethodInfo().getName();

        String before = "";
        String after = "";

        ClassPool pool = ClassPool.getDefault();
        
        before = before(methodName, before);
        after = after(methodName, after);

        if (!Modifier.isNative(method.getModifiers()))
        {
            method.insertBefore(before);
            method.insertAfter(after);
        }
    }

    private boolean isMonitorDesired(CtMethod method, String methodName)
    {
        boolean isPublic = false; 
        if (PUBLIC) {
            isPublic = method.getMethodInfo().getAccessFlags() == AccessFlag.PUBLIC;   
        }
        return (MAIN && methodName.equals("main")) || (!MAIN && PUBLIC && isPublic) || (!MAIN && !PUBLIC);
    }

    private String before(String methodName, String before)
    {
        StringBuffer sb = new StringBuffer(before);
        sb.append("{");
        
        sb.append("if (_logger == null) _logger = LogManager.getLogger();");
        sb.append("String _params = ").append(QUOTE).append(QUOTE).append(";");
        sb.append("for (int i = 0; i < $args.length; i++) {_params += String.valueOf($args[i]) + \", \";}");
        sb.append("_logger.").append(LOG_LEVEL).append("(")
        .append(QUOTE).append("->]  {}({})").append(QUOTE).append(", ")
        .append("new Object[] {")
        .append(QUOTE).append(methodName).append(QUOTE).append(", ")
        .append("_params.substring(0, _params.length() > 1 ? _params.length() - 2 : _params.length())")
        .append("}")
        .append(");");
        
        sb.append("}");
        
        
        return sb.toString();
    }

    private String after(String methodName, String after)
    {
        StringBuffer sb = new StringBuffer(after);
        sb.append("{");
        
        
        sb.append("StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();");
        sb.append("String _caller = \"_unknown_\";");
        
        sb.append("if (stackTraceElements.length > 2)  { ");
        sb.append(" StackTraceElement stackTraceElement = stackTraceElements[2];");
        sb.append(" _caller = stackTraceElement.getClassName(); ");
        sb.append("}");

        sb.append("String _params = ").append(QUOTE).append(QUOTE).append(";");
        sb.append("for (int i = 0; i < $args.length; i++) {");
        sb.append("_params += String.valueOf($args[i]) + \", \";");//single method input param
        sb.append("}");
        
        sb.append("_logger.").append(LOG_LEVEL).append("(")
        .append(QUOTE).append("[->  {}({}) => {} [caller: {}, duration: {} ms.]").append(QUOTE).append(", ")
        .append("new Object[] {")
        .append(QUOTE).append(methodName).append(QUOTE).append(", ")//method name
        .append("_params.substring(0, _params.length() > 1 ? _params.length() - 2 : _params.length())").append(", ")//method params without trailing comma
        .append("String.valueOf($_)").append(", ")//return value
        .append("_caller")
        .append(");");
        
        sb.append("}");
        
        return sb.toString();
    }
}