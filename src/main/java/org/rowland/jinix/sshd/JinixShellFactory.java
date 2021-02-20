package org.rowland.jinix.sshd;

import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.shell.ShellFactory;

/**
 * Created by rsmith on 12/29/2016.
 */
public class JinixShellFactory implements ShellFactory {

    @Override
    public Command createShell(ChannelSession channelSession) {
        return new JinixShell();
    }
}
