package org.rowland.jinix.sshd;

import org.apache.sshd.common.Factory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.shell.InvertedShellWrapper;

/**
 * Created by rsmith on 12/29/2016.
 */
public class JinixShellFactory implements Factory<Command> {

    @Override
    public Command create() {
        return new JinixShell();
    }
}
