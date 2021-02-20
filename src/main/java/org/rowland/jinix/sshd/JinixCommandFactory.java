package org.rowland.jinix.sshd;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Created by rsmith on 3/4/2017.
 */
public class JinixCommandFactory implements CommandFactory {

    @Override
    public Command createCommand(ChannelSession channelSession, String s) throws IOException {
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
        public void start(ChannelSession channelSession, Environment env) throws IOException {

        }

        @Override
        public void destroy(ChannelSession channelSession) throws Exception {

        }
    }
}
