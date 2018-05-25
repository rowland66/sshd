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
import org.rowland.jinix.terminal.*;

import java.io.*;
import java.rmi.RemoteException;
import java.util.*;

/**
 * A sshd server Command that provides a JinixShell (jsh).
 */
public class JinixShell implements Command, SessionAware, SignalListener {

    private static int debugInc = 0;
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

        try {
            Map<PtyMode, Integer> modes = resolveShellTtyOptions(env.getPtyModes());
            short terminalId = Sshd.terminalServer.createTerminal();

            TerminalAttributes termAttrs = Sshd.terminalServer.getTerminalAttributes(terminalId);
            mapToJinixInputModes(modes, termAttrs.inputModes);
            mapToJinixOutputModes(modes, termAttrs.outputModes);
            mapToJinixLocalModes(modes, termAttrs.localModes);
            mapJinixSpecialCharacters(modes, termAttrs.specialCharacterMap);
            Sshd.terminalServer.setTerminalAttributes(terminalId, termAttrs);

            JinixFileDescriptor masterFileDescriptor =
                    new JinixFileDescriptor(Sshd.terminalServer.getTerminalMaster(terminalId));
            JinixFileDescriptor slaveFileDescriptor =
                    new JinixFileDescriptor(Sshd.terminalServer.getTerminalSlave(terminalId));

            try {
                Properties shellEnv = new Properties();
                shellEnv.put("TERM", env.getEnv().get(Environment.ENV_TERM));
                shellEnv.put("LINES",env.getEnv().get(Environment.ENV_LINES));
                shellEnv.put("COLUMNS",env.getEnv().get(Environment.ENV_COLUMNS));
                shellEnv.put("LOGNAME",env.getEnv().get(Environment.ENV_USER));

                // , "-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5557,server=y,suspend=n"
                int debugPort = 5557 + debugInc;
                debugInc++;
                shellPid = JinixRuntime.getRuntime().exec(null, "/bin/jsh.jar", new String[]{"/home"}, -1, -1,
                        slaveFileDescriptor, slaveFileDescriptor, slaveFileDescriptor);

                Sshd.processManager.setProcessTerminalId(shellPid, terminalId);
                Sshd.terminalServer.linkProcessToTerminal(terminalId, shellPid);

                env.addSignalListener(this, Signal.WINCH);

            } catch (FileNotFoundException | InvalidExecutableException e) {
                throw new RuntimeException(e);
            } finally {
                slaveFileDescriptor.close();
            }

            // This is confusing. The inputstream is the output from the exec'd process, and the output stream
            // is the input.
            shellOut = new BufferedInputStream(new JinixFileInputStream(masterFileDescriptor));
            shellIn = new JinixFileOutputStream(masterFileDescriptor);

            outputThread = new OutputThread(shellOut);
            inputThread = new InputThread(shellIn);

            outputThread.start();
            inputThread.start();
        } catch (Exception e) {
            e.printStackTrace(System.err);
            throw e;
        }
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

    @Override
    public void signal(Signal signal) {
        System.out.println("Received signal: "+signal.toString());
        //JinixRuntime.getRuntime().sendSignal(shellPid, org.rowland.jinix.proc.ProcessManager.Signal.WINCH);
    }

    private void mapToJinixInputModes(Map<PtyMode, Integer> ptyModes, Set<InputMode> termInputAttrs) {

        if (ptyModes.containsKey(PtyMode.INLCR)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.INLCR)) termInputAttrs.add(InputMode.INLCR);
            else termInputAttrs.remove(InputMode.INLCR);
        }
        if (ptyModes.containsKey(PtyMode.IGNCR)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.IGNCR)) termInputAttrs.add(InputMode.IGNCR);
            else termInputAttrs.remove(InputMode.IGNCR);
        }
        if (ptyModes.containsKey(PtyMode.ICRNL)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.ICRNL)) termInputAttrs.add(InputMode.ICRNL);
            else termInputAttrs.remove(InputMode.ICRNL);
        }
        if (ptyModes.containsKey(PtyMode.IUCLC)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.IUCLC)) termInputAttrs.add(InputMode.IUCLC);
            else termInputAttrs.remove(InputMode.IUCLC);
        }
        if (ptyModes.containsKey(PtyMode.IXON)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.IXON)) termInputAttrs.add(InputMode.IXON);
            else termInputAttrs.remove(InputMode.IXON);
        }
        if (ptyModes.containsKey(PtyMode.IXANY)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.IXANY)) termInputAttrs.add(InputMode.IXANY);
            else termInputAttrs.remove(InputMode.IXANY);
        }
        if (ptyModes.containsKey(PtyMode.IXOFF)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.IXOFF)) termInputAttrs.add(InputMode.IXOFF);
            else termInputAttrs.remove(InputMode.IXOFF);
        }
        if (ptyModes.containsKey(PtyMode.IMAXBEL)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.IMAXBEL)) termInputAttrs.add(InputMode.IMAXBEL);
            else termInputAttrs.remove(InputMode.IMAXBEL);
        }
    }

    private void mapToJinixOutputModes(Map<PtyMode, Integer> ptyModes, Set<OutputMode> termOutputAttrs) {

        if (ptyModes.containsKey(PtyMode.OPOST)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.OPOST)) termOutputAttrs.add(OutputMode.OPOST);
            else termOutputAttrs.remove(OutputMode.OPOST);
        }
        if (ptyModes.containsKey(PtyMode.OLCUC)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.OLCUC)) termOutputAttrs.add(OutputMode.OLCUC);
            else termOutputAttrs.remove(OutputMode.OLCUC);
        }
        if (ptyModes.containsKey(PtyMode.ONLCR)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.ONLCR)) termOutputAttrs.add(OutputMode.ONLCR);
            else termOutputAttrs.remove(OutputMode.ONLCR);
        }
        if (ptyModes.containsKey(PtyMode.OCRNL)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.OCRNL)) termOutputAttrs.add(OutputMode.OCRNL);
            else termOutputAttrs.remove(OutputMode.OCRNL);
        }
        if (ptyModes.containsKey(PtyMode.ONOCR)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.ONOCR)) termOutputAttrs.add(OutputMode.ONOCR);
            else termOutputAttrs.remove(OutputMode.ONOCR);
        }
        if (ptyModes.containsKey(PtyMode.ONLRET)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.ONLRET)) termOutputAttrs.add(OutputMode.ONLRET);
            else termOutputAttrs.remove(OutputMode.ONLRET);
        }
    }

    private void mapToJinixLocalModes(Map<PtyMode, Integer> ptyModes, Set<LocalMode> termLocalAttrs) {
        if (ptyModes.containsKey(PtyMode.ISIG)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.ISIG)) termLocalAttrs.add(LocalMode.ISIG);
            else termLocalAttrs.remove(LocalMode.ISIG);
        }
        if (ptyModes.containsKey(PtyMode.ICANON)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.ICANON)) termLocalAttrs.add(LocalMode.ICANON);
            else termLocalAttrs.remove(LocalMode.ICANON);
        }
        if (ptyModes.containsKey(PtyMode.XCASE)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.XCASE)) termLocalAttrs.add(LocalMode.XCASE);
            else termLocalAttrs.remove(LocalMode.XCASE);
        }
        if (ptyModes.containsKey(PtyMode.ECHO)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.ECHO)) termLocalAttrs.add(LocalMode.ECHO);
            else termLocalAttrs.remove(LocalMode.ECHO);
        }
        if (ptyModes.containsKey(PtyMode.TOSTOP)) {
            if (PtyMode.getBooleanSettingValue(ptyModes, PtyMode.TOSTOP)) termLocalAttrs.add(LocalMode.TOSTOP);
            else termLocalAttrs.remove(LocalMode.TOSTOP);
        }
    }

    private void mapJinixSpecialCharacters(Map<PtyMode, Integer> ptyModes, Map<SpecialCharacter, Byte> termAttrsChars) {
        if (ptyModes.get(PtyMode.VINTR) != null) termAttrsChars.put(SpecialCharacter.VINTR, (byte) (0xff & ptyModes.get(PtyMode.VINTR)));
        if (ptyModes.get(PtyMode.VQUIT) != null) termAttrsChars.put(SpecialCharacter.VQUIT, (byte) (0xff & ptyModes.get(PtyMode.VQUIT)));
        if (ptyModes.get(PtyMode.VERASE) != null) termAttrsChars.put(SpecialCharacter.VERASE, (byte) (0xff & ptyModes.get(PtyMode.VERASE)));
        if (ptyModes.get(PtyMode.VKILL) != null) termAttrsChars.put(SpecialCharacter.VKILL, (byte) (0xff & ptyModes.get(PtyMode.VKILL)));
        if (ptyModes.get(PtyMode.VEOF) != null) termAttrsChars.put(SpecialCharacter.VEOF, (byte) (0xff & ptyModes.get(PtyMode.VEOF)));
        if (ptyModes.get(PtyMode.VEOL) != null) termAttrsChars.put(SpecialCharacter.VEOL, (byte) (0xff & ptyModes.get(PtyMode.VEOL)));
        if (ptyModes.get(PtyMode.VSUSP) != null) termAttrsChars.put(SpecialCharacter.VSUSP, (byte) (0xff & ptyModes.get(PtyMode.VSUSP)));
        if (ptyModes.get(PtyMode.VSTOP) != null) termAttrsChars.put(SpecialCharacter.VSTOP, (byte) (0xff & ptyModes.get(PtyMode.VSTOP)));
        if (ptyModes.get(PtyMode.VSTART) != null) termAttrsChars.put(SpecialCharacter.VSTART, (byte) (0xff & ptyModes.get(PtyMode.VSTART)));
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
                    //System.out.print(Integer.toHexString(n));
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
