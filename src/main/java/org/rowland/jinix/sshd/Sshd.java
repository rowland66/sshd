package org.rowland.jinix.sshd;

import org.apache.commons.cli.*;
import org.apache.sshd.common.config.VersionProperties;
import org.apache.sshd.server.ServerBuilder;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.password.PasswordChangeRequiredException;
import org.apache.sshd.server.channel.ChannelSessionFactory;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.session.ServerSession;
import org.rowland.jinix.exec.ExecServer;
import org.rowland.jinix.exec.InvalidExecutableException;
import org.rowland.jinix.lang.JinixRuntime;
import org.rowland.jinix.lang.ProcessSignalHandler;
import org.rowland.jinix.naming.NameSpace;
import org.rowland.jinix.proc.ProcessManager;
import org.rowland.jinix.terminal.TermServer;

import javax.naming.Context;
import javax.naming.NamingException;
import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.RMISocketFactory;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

/**
 * Jinix ssh server.
 */
public class Sshd extends SshServer {

    private static final String DEFAULT_CONFIG_FILE = "/config/ssh/sshd.config";
    private static final String DEFAULT_HOST_DSS_KEYS = "/config/ssh/ssh_host_dss_keys.ser";

    private static final String LOG_FILE_PROPERTY_NAME = "LogFile";
    private static final String DEFAULT_LOG_FILE = "/var/log/sshd.log";

    private static final String PID_FILE_PROPERTY_NAME = "PidFile";
    private static final String DEFAULT_PID_FILE = "/var/run/sshd.pid";

    private static final String PORT_PROPERTY_NAME  = "Port";
    private static final String DEFAULT_PORT = "8000";

    static TermServer terminalServer;
    static ExecServer execServer;
    static ProcessManager processManager;
    static SshServer server;
    static Thread mainThread;

    public static void main(String[] args) {

        CommandLine cmdLine = parseCommandLineOptions(args);

        if (!JinixRuntime.getRuntime().isForkChild()) {
            if (!cmdLine.hasOption("D") && !cmdLine.hasOption("d")) {
                try {
                    int pid = JinixRuntime.getRuntime().fork();
                    if (pid > 0) {
                        System.out.println("Starting sshd with process ID: "+pid);
                        return;
                    } else {
                        throw new RuntimeException("fork return error");
                    }
                } catch (FileNotFoundException | InvalidExecutableException e) {
                    throw new RuntimeException(e);
                }
            } else {
                if (cmdLine.hasOption("d")) {
                    // turn on debug logging.
                }
            }
        }

        // Processing after fork() starts here.
        mainThread = Thread.currentThread();

        // Disassociate ourselves from our parents session group and process group.
        JinixRuntime.getRuntime().setProcessSessionId();

        try {
            System.in.close();
            System.out.close();
            System.err.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String configFile = DEFAULT_CONFIG_FILE;
        if (cmdLine.hasOption("f")) {
            configFile = cmdLine.getOptionValue("f");
        }

        Properties sshdConfig = new Properties();
        InputStream config = null;
        try {
            config = Files.newInputStream(Paths.get(configFile), StandardOpenOption.READ);
            sshdConfig.load(new InputStreamReader(config));
            config.close();
        } catch (NoSuchFileException e) {
            //Using default setting
        } catch (IOException e) {
            System.exit(0);
        }

        String sshdPidFile = sshdConfig.getProperty(PID_FILE_PROPERTY_NAME, DEFAULT_PID_FILE);
        try {
            OutputStream sshdPidFileStream = Files.newOutputStream(Paths.get(sshdPidFile),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            PrintStream pidPrinter = new PrintStream(sshdPidFileStream);
            pidPrinter.println(JinixRuntime.getRuntime().getPid());
            pidPrinter.close();
        } catch (IOException e) {
            throw new RuntimeException("IO Failure opening " + sshdPidFile, e);
        }

        String sshdLog = sshdConfig.getProperty(LOG_FILE_PROPERTY_NAME, DEFAULT_LOG_FILE);
        try {
            OutputStream log = Files.newOutputStream(Paths.get(sshdLog),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            System.setOut(new PrintStream(log));
            System.setErr(new PrintStream(log));
        } catch (IOException e) {
            throw new RuntimeException("IO Failure opening " + sshdLog, e);
        }

        try {
            Context jinixNamingContext = JinixRuntime.getRuntime().getNamingContext();
            terminalServer = (TermServer) jinixNamingContext.lookup(TermServer.SERVER_NAME);

            execServer = (ExecServer) jinixNamingContext.lookup(ExecServer.SERVER_NAME);

            processManager = (ProcessManager) jinixNamingContext.lookup(ProcessManager.SERVER_NAME);
        } catch (NamingException e) {
            throw new RuntimeException("Failure locating Jinix servers.", e);
        }

        int port;
        try {
            port = Integer.parseInt(sshdConfig.getProperty(PORT_PROPERTY_NAME, DEFAULT_PORT));
        } catch (NumberFormatException e) {
            port = Integer.parseInt(DEFAULT_PORT);
        }

        server = ServerBuilder.builder().build();
        server.setIoServiceFactoryFactory(new org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory());
        server.setPort(port);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(Paths.get(DEFAULT_HOST_DSS_KEYS)));
        server.setShellFactory(new JinixShellFactory());

        server.setPasswordAuthenticator(new PasswordAuthenticator() {
            @Override
            public boolean authenticate(String s, String s1, ServerSession serverSession) throws PasswordChangeRequiredException {
                return true;
            }
        });

        JinixRuntime.getRuntime().registerSignalHandler(new ProcessSignalHandler() {
            @Override
            public boolean handleSignal(ProcessManager.Signal signal) {
                if (signal == ProcessManager.Signal.TERMINATE) {
                    try {
                        System.out.println("TERM signal received, shutting down...");
                        server.stop();
                    } catch (IOException e) {
                        System.err.println("IOException stopping server");
                        e.printStackTrace(System.err);
                    }
                    mainThread.interrupt();
                    return true;
                }
                return false;
            }
        });

        try {
            server.start();
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }

        try {
            Thread.sleep(Integer.MAX_VALUE);
        } catch (InterruptedException e) {
            System.out.println("Shutdown complete");
        }
    }

    private static CommandLine parseCommandLineOptions(String[] args) {

        CommandLineParser parser = new DefaultParser();

        Options options = new Options();

        options.addOption("D", "noDaemon", false, "do not detach and run as a daemon");
        options.addOption("d", "debug", false, "run in debug mode");
        options.addOption("f", "configFile", true, "name of the configuration file. Default is /config/ssh/sshd.config");

        try {
            CommandLine cmdLine = parser.parse(options, args);
            return cmdLine;
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            Map<String,String> versionProperties = VersionProperties.getVersionProperties();
            HelpFormatter formatter = new HelpFormatter();
            formatter.setArgName("[FILE]...");
            formatter.printHelp("sshd",
                    "Bogus",
                    options,
                    "With no FILE, or when FILE is -, read standard input.",
                    true);
            return null;
        }
    }
}
