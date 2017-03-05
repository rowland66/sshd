package org.rowland.jinix.sshd;

import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by rsmith on 3/4/2017.
 */
public class JinixCommandFactory implements CommandFactory {

    @Override
    public Command createCommand(String command) {
        return new CopyCommand();
    }

    private static class CopyCommand implements Command {
        @Override
        public void setInputStream(InputStream in) {

        }

        @Override
        public void setOutputStream(OutputStream out) {

        }

        @Override
        public void setErrorStream(OutputStream err) {

        }

        @Override
        public void setExitCallback(ExitCallback callback) {

        }

        @Override
        public void start(Environment env) throws IOException {

        }

        @Override
        public void destroy() throws Exception {

        }
    }
}
