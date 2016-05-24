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
import java.util.Random;

import org.apache.logging.log4j.Logger;

import com.jamonapi.Monitor;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.AccessFlag;

public class Agent implements ClassFileTransformer {

	private static final boolean MAIN_ONLY = false;
	private static final boolean PUBLIC_ONLY = false;

	private static final String LOG_LEVEL_DEBUG = "debug";
	private static final String LOG_LEVEL_INFO = "info";
	private static final String LOG_LEVEL_ERROR = "error";
	private static final String LOG_LEVEL_FATAL = "fatal";
	private final static String QUOTE = "\"";

	private static final String LOG_LEVEL = LOG_LEVEL_DEBUG;

	private static ArrayList<String> blackList;
	private static ArrayList<String> whiteList;

	private static boolean monitorDesired;
	private String _var_monitor;

	private Random random = new Random();

	static {

		List<String> linesBlack = null;
		List<String> linesWhite = null;

		Path pathBlack = Paths.get("./tracy.blacklist");
		Path pathWhite = Paths.get("./tracy.whitelist");
		try {
			linesBlack = Files.readAllLines(pathBlack, Charset.defaultCharset());
			linesWhite = Files.readAllLines(pathWhite, Charset.defaultCharset());
		} catch (IOException e) {
			e.printStackTrace();
		}

		blackList = new ArrayList<String>(linesBlack != null ? linesBlack.size() : 1);
		for (String line : linesBlack) {
			blackList.add(line);
		}

		whiteList = new ArrayList<String>(linesWhite != null ? linesWhite.size() : 1);
		for (String line : linesWhite) {
			whiteList.add(line);
		}
	}

	public static void premain(String agentArgument, Instrumentation instrumentation) {
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
	public byte[] transform(ClassLoader loader, String clazzName, Class clazz, ProtectionDomain domain, byte[] bytes) {
		if (clazzName.startsWith("java") || clazzName.startsWith("sun/") || clazzName.startsWith("com/sun/")) {
			return bytes;
		}
		byte[] transformedClass = transformClass(clazzName, clazz, bytes);
		return transformedClass;
	}

	@SuppressWarnings({ "rawtypes" })
	private byte[] transformClass(String clazzName, Class clazz, byte[] bytes) {
		ClassPool pool = ClassPool.getDefault();

		pool.importPackage("org.apache.logging.log4j");
		pool.importPackage("com.jamonapi");

		// --- Start filtering
		filterClasses: {
			for (String entry : blackList) {
				if (clazzName.startsWith(entry))
					return bytes;
			}

			boolean whiteEntryFound = whiteList.isEmpty();
			for (String entry : whiteList) {
				if (clazzName.startsWith(entry)) {
					whiteEntryFound = true;
					break;
				}
			}
			if (!whiteEntryFound)
				return bytes;
		}
		// --- End filtering

		System.out.println(String.format("### Instrument %s now ###", clazzName));

		CtClass ctClass = null;
		try {
			ctClass = pool.makeClass(new java.io.ByteArrayInputStream(bytes));
			if (!ctClass.isInterface()) {
				try {
					// CtClass cc = pool.get(className);
					CtClass cc = ctClass;
					CtClass ctLogger = pool.get(Logger.class.getCanonicalName());
					CtField f = new CtField(ctLogger, "_jalot_logger", cc);
					f.setModifiers(Modifier.PRIVATE);
					f.setModifiers(Modifier.STATIC);
					cc.addField(f);
					// cc.writeFile("C:\\temp");
				} catch (NotFoundException e) {
					e.printStackTrace();
				} catch (CannotCompileException e) {
					e.printStackTrace();
				}

				CtMethod[] ctMethods = ctClass.getDeclaredMethods();
				for (int i = 0; i < ctMethods.length; i++) {
					if (!ctMethods[i].isEmpty()) {
						transformMethod(ctClass, ctMethods[i]);
					}
				}
				bytes = ctClass.toBytecode();
			}
		} catch (Exception e) {
			e.printStackTrace(System.err);
		} finally {
			if (ctClass != null) {
				ctClass.detach();
			}
		}
		return bytes;
	}

	private void transformMethod(CtClass ctClass, CtMethod method) throws NotFoundException, CannotCompileException {

		String methodName = method.getLongName();
		_var_monitor = new StringBuilder("_jalot_monitor_").append(method.getMethodInfo().getName()).append("_")
				.append(Integer.toString(random.nextInt(99999))).toString();

		monitorDesired = isMonitorDesired(method, methodName);

		String before = "";
		String after = "";

		ClassPool pool = ClassPool.getDefault();
		CtClass ctMonitor = pool.get(Monitor.class.getCanonicalName());
		CtField f = new CtField(ctMonitor, _var_monitor, ctClass);
		f.setModifiers(Modifier.PRIVATE);
		f.setModifiers(Modifier.STATIC);
		ctClass.addField(f);

		before = before(methodName, before);
		after = after(methodName, after);

		if (!Modifier.isNative(method.getModifiers())) {
			method.insertBefore(before);
			method.insertAfter(after);
		}
	}

	private boolean isMonitorDesired(CtMethod method, String methodName) {
		boolean isPublic = false;
		if (PUBLIC_ONLY) {
			isPublic = method.getMethodInfo().getAccessFlags() == AccessFlag.PUBLIC;
		}
		return (MAIN_ONLY && methodName.equals("main")) || (!MAIN_ONLY && PUBLIC_ONLY && isPublic)
				|| (!MAIN_ONLY && !PUBLIC_ONLY);
	}

	private String before(String methodName, String before) {
		StringBuffer sb = new StringBuffer(before);
		sb.append("{");

		sb.append("if (_jalot_logger == null) _jalot_logger = LogManager.getLogger();");
		sb.append("String _jalot_params = ").append(QUOTE).append(QUOTE).append(";");
		sb.append("for (int i = 0; i < $args.length; i++) {_jalot_params += String.valueOf($args[i]) + \", \";}");
		sb.append("_jalot_logger.").append(LOG_LEVEL).append("(").append(QUOTE).append("->]  {} [f({})]").append(QUOTE)
				.append(", ").append("new Object[] {").append(QUOTE).append(methodName).append(QUOTE).append(", ")
				.append("_jalot_params.substring(0, _jalot_params.length() > 1 ? _jalot_params.length() - 2 : _jalot_params.length())")
				.append("}").append(");");

		if (monitorDesired) {
			sb.append(_var_monitor).append(" = MonitorFactory.start(\"").append(methodName).append("\");");
		}
		sb.append("}");

		return sb.toString();
	}

	private String after(String methodName, String after) {
		StringBuffer sb = new StringBuffer(after);
		sb.append("{");

		sb.append("Monitor _jalot_monitor_dur = null;");
		if (monitorDesired) {
			sb.append("_jalot_monitor_dur = ").append(_var_monitor).append(".stop();");
		}

		sb.append("StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();");
		sb.append("String _jalot_caller = \" - \";");

		sb.append("if (stackTraceElements.length > 2)  { ");
		sb.append(" StackTraceElement stackTraceElement = stackTraceElements[2];");
		sb.append(" _jalot_caller = stackTraceElement.getClassName(); ");
		sb.append("}");

		sb.append("String _jalot_params = ").append(QUOTE).append(QUOTE).append(";");
		sb.append("for (int i = 0; i < $args.length; i++) {");
		sb.append("_jalot_params += String.valueOf($args[i]) + \", \";");// single method input param
		sb.append("}");

		sb.append("_jalot_logger.").append(LOG_LEVEL).append("(").append(QUOTE)
				.append("[->  {} [f({}):{}, caller:{}, duration:{} ms.]").append(QUOTE).append(", ")
				.append("new Object[] {").append(QUOTE).append(methodName).append(QUOTE).append(", ")// method
																										// name
				.append("_jalot_params.substring(0, _jalot_params.length() > 1 ? _jalot_params.length() - 2 : _jalot_params.length())")
				.append(", ")// method params without trailing comma
				.append("String.valueOf($_)").append(", ")// return value
				.append("_jalot_caller");
		if (monitorDesired) {
			sb.append(", ")// caller
					.append("String.valueOf(_jalot_monitor_dur.getLastValue())");// duration
		}
		sb.append("}").append(");");

		if (methodName.contains(".main(java.lang.String[])")) {
			sb.append("String _jalot_report = MonitorFactory.getRootMonitor().getReport(4, \"desc\");");
			sb.append("_jalot_logger.").append(LOG_LEVEL).append("(\"")
					.append("___________________________________ performance report ___________________________________")
					.append("\");");
			sb.append("_jalot_logger.").append(LOG_LEVEL).append("(\"")
					.append("---8<-----------------8<-----------------8<-----------------8<-----------------8<--------------")
					.append("\");");
			sb.append("_jalot_logger.").append(LOG_LEVEL).append("(").append("_jalot_report").append(");");
			sb.append("_jalot_logger.").append(LOG_LEVEL).append("(\"")
					.append("--->8----------------->8----------------->8----------------->8----------------->8--------------")
					.append("\");");
		}

		sb.append("}");

		return sb.toString();
	}
}