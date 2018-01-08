package org.rowland.jinix.sshd;

import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.ValidateUtils;
import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.PuttyRequestHandler;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.session.ServerSessionHolder;
import org.rowland.jinix.exec.ExecServer;
import org.rowland.jinix.exec.InvalidExecutableException;
import org.rowland.jinix.io.JinixFileDescriptor;
import org.rowland.jinix.io.JinixFileInputStream;
import org.rowland.jinix.io.JinixFileOutputStream;
import org.rowland.jinix.lang.JinixRuntime;
import org.rowland.jinix.proc.ProcessManager;

import java.io.*;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * A sshd server Command that provides a JinixShell (jsh).
 */
public class JinixShell implements Command, SessionAware {

    private int shellPid;
    private ServerSession session;
    private InputStream in;
    private OutputStream out;
    private OutputStream err;
    private ExitCallback exitCallback;

    OutputStream shellIn;
    InputStream shellOut;
    OutputThread outputThread = null;
    InputThread inputThread = null;

    JinixShell() {
    }

    public void start(Environment env) throws IOException {

        Map<PtyMode, Integer> modes = resolveShellTtyOptions(env.getPtyModes());
        Map<org.rowland.jinix.terminal.PtyMode, Integer> jinixModes = mapTtyOptionsToJinix(modes);
        short terminalId = Sshd.terminalServer.createTerminal(jinixModes);
        JinixFileDescriptor masterFileDescriptor =
                new JinixFileDescriptor(Sshd.terminalServer.getTerminalMaster(terminalId));
        JinixFileDescriptor slaveFileDescriptor =
                new JinixFileDescriptor(Sshd.terminalServer.getTerminalSlave(terminalId));

        try {
            slaveFileDescriptor.getHandle().duplicate();
            slaveFileDescriptor.getHandle().duplicate();

            shellPid = JinixRuntime.getRuntime().exec(null, "/bin/jsh.jar", new String[]{"/home"}, 0,
                    slaveFileDescriptor, slaveFileDescriptor, slaveFileDescriptor);

            Sshd.processManager.setProcessTerminalId(shellPid, terminalId);
            Sshd.terminalServer.linkProcessToTerminal(terminalId, shellPid);

        } catch (FileNotFoundException | InvalidExecutableException e) {
            throw new RuntimeException(e);
        }

        // This is confusing. The inputstream is the output from the exec'd process, and the output stream
        // is the input.
        shellOut = new BufferedInputStream(new JinixFileInputStream(masterFileDescriptor));
        shellIn = new JinixFileOutputStream(masterFileDescriptor);

        outputThread = new OutputThread(shellOut);
        inputThread = new InputThread(shellIn);

        outputThread.start();
        inputThread.start();
    }

    //@Override
    public void setSession(ServerSession session) {
        this.session = ValidateUtils.checkNotNull(session, "No server session");
    }

    // for some reason these modes provide best results BOTH with Linux SSH client and PUTTY
    protected Map<PtyMode, Integer> resolveShellTtyOptions(Map<PtyMode, Integer> modes) {
        if (PuttyRequestHandler.isPuttyClient(session)) {
            return PuttyRequestHandler.resolveShellTtyOptions(modes);
        } else {
            return modes;
        }
    }

    public void destroy() throws Exception {
        Sshd.processManager.sendSignal(shellPid, ProcessManager.Signal.HANGUP);
    }

    public void setInputStream(InputStream in) {
        this.in = in;
    }

    public void setOutputStream(OutputStream out) {
        this.out = out;
    }

    public void setErrorStream(OutputStream err) {
        this.err = err;
    }

    public void setExitCallback(ExitCallback callback) {
        this.exitCallback = callback;
    }

    /**
     * Convert PtyModes from the Mina sshd PtyMode enum to the Jinix enum
     *
     * @param inputMap
     * @return
     */
    private Map<org.rowland.jinix.terminal.PtyMode, Integer>
            mapTtyOptionsToJinix(Map<PtyMode, Integer> inputMap) {
        Map<org.rowland.jinix.terminal.PtyMode, Integer> outputMap =
                new HashMap<org.rowland.jinix.terminal.PtyMode, Integer>(inputMap.size());
        for (Map.Entry<PtyMode, ?> inputEntry : inputMap.entrySet()) {
            outputMap.put(org.rowland.jinix.terminal.PtyMode.fromInt(inputEntry.getKey().toInt()),
                    (Integer) inputEntry.getValue());
        }
        return outputMap;
    }

    /**
     * Thread to read bytes from the jsh's standards output and error, and write them to the
     * ssh channel output stream.
     */
    private class OutputThread extends Thread {

        private InputStream is;

        private OutputThread(InputStream input) {
            super("Output Thread");
            this.is = input;
        }

        @Override
        public void run() {
            try {
                int n;
                int avail = 0;
                while ((n = is.read()) > 0) {
                    out.write(n);
                    if (avail == 0) {
                        avail = is.available();
                        if (avail == 0) {
                            out.flush();
                            continue;
                        }
                    }
                    avail--;
                }

                shellOut.close();
                shellIn.close();

                exitCallback.onExit(0);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Thread to read bytes from the ssh channels input stream, and write them to the
     * jsh's input stream.
     */
    private class InputThread extends Thread {

        private OutputStream os;

        private InputThread(OutputStream output) {
            super("Input Thread");
            this.os = output;
        }

        @Override
        public void run() {
            try {
                int b;
                while ((b = in.read()) > 0) {
                    os.write(b);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
