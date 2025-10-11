/*
 * Copyright (c) 2024 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package com.sun.enterprise.admin.cli;

import com.sun.enterprise.admin.cli.remote.RemoteCLICommand;
import com.sun.enterprise.admin.util.CommandModelData;

import jakarta.inject.Inject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.glassfish.api.Param;
import org.glassfish.api.admin.CommandException;
import org.glassfish.api.admin.CommandModel;
import org.glassfish.api.admin.CommandModel.ParamModel;
import org.glassfish.api.admin.CommandValidationException;
import org.glassfish.api.admin.InvalidCommandException;
import org.glassfish.common.util.admin.CommandModelImpl;
import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.DynamicConfiguration;
import org.glassfish.hk2.api.DynamicConfigurationService;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.main.jdke.i18n.LocalStringsImpl;
import org.jline.builtins.Completers;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.ParsedLine;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.AggregateCompleter;
import org.jline.reader.impl.completer.ArgumentCompleter;
import org.jline.reader.impl.completer.NullCompleter;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.reader.impl.completer.SystemCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jvnet.hk2.annotations.Service;

/**
 * A scaled-down implementation of multi-mode command.
 *
 * @author केदार(km@dev.java.net)
 * @author Bill Shannon
 */
@Service(name = "multimode")
@PerLookup
public class MultimodeCommand extends CLICommand {

    @Inject
    private ServiceLocator habitat;

    @Inject
    private CLIContainer container;

    @Param(optional = true, shortName = "f")
    private File file;

    @Param(name = "printprompt", optional = true)
    private Boolean printPromptOpt;
    private boolean printPrompt;

    @Param(optional = true)
    private String encoding;
    private boolean echo; // saved echo flag
    private static final LocalStringsImpl strings = new LocalStringsImpl(MultimodeCommand.class);

    private static final boolean DISABLE_JLINE = Boolean.getBoolean("glassfish.disable.jline") || Boolean.parseBoolean(System.getenv("AS_DISABLE_JLINE"));

    /**
     * The validate method validates that the type and quantity of parameters and operands matches the requirements for this
     * command. The validate method supplies missing options from the environment.
     */
    @Override
    protected void validate() throws CommandException, CommandValidationException {
        if (printPromptOpt != null) {
            printPrompt = printPromptOpt.booleanValue();
        } else {
            printPrompt = programOpts.isInteractive();
        }
        /*
         * Save value of --echo because CLICommand will reset it
         * before calling our executeCommand method but we want it
         * to also apply to all commands in multimode.
         */
        echo = programOpts.isEcho();
    }

    /**
     * In the usage message modify the --printprompt option to have a default based on the --interactive option.
     */
    @Override
    protected Collection<ParamModel> usageOptions() {
        Collection<ParamModel> opts = commandModel.getParameters();
        Set<ParamModel> uopts = new LinkedHashSet<>();
        ParamModel p = new CommandModelData.ParamModelData("printprompt", boolean.class, true,
                Boolean.toString(programOpts.isInteractive()));
        for (ParamModel pm : opts) {
            if (pm.getName().equals("printprompt")) {
                uopts.add(p);
            } else {
                uopts.add(pm);
            }
        }
        return uopts;
    }

    @Override
    protected int executeCommand() throws CommandException, CommandValidationException {
        BufferedReader reader = null;
        // restore echo flag, saved in validate
        programOpts.setEcho(echo);
        try {
            if (file == null) {
                System.out.println(strings.get("multimodeIntro"));
                Prompter prompter = getPrompter();
                return executeCommands(prompter);
            } else {
                printPrompt = false;
                if (!file.canRead()) {
                    throw new CommandException("File: " + file + " can not be read");
                }
                if (encoding == null) {
                    reader = new BufferedReader(new FileReader(file, Charset.defaultCharset()));
                } else {
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), encoding));
                }
            }
            return executeCommands(new BufferedReaderPrompter(reader));
        } catch (IOException e) {
            throw new CommandException(e);
        } finally {
            try {
                if (file != null && reader != null) {
                    reader.close();
                }
            } catch (IOException e) {
                // ignore it
            }
        }
    }

    private Prompter basicPrompter() throws IOException {
        return new BufferedReaderPrompter(new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset())));
    }

    private Prompter getPrompter() throws IOException {
        if (DISABLE_JLINE) {
            return basicPrompter();
        }
        try {
            return new JLinePrompter();
        } catch (Exception e) {
            System.err.println("Warning: Failed to initialize the advanced console (JLine). Features like history and auto-completion will be disabled.");
            System.err.println("Cause: " + e.getMessage());
            return basicPrompter();
        }
    }

    private static Completer getCompleterForLocalCommand(String commandName, Class<?> klass) {
        List<OptionAndCompleter> optionsDesc = CommandModelImpl.init(klass, null, null)
                .entrySet()
                .stream()
                .flatMap(parameter -> toOptionAndCompleter(parameter.getKey(), parameter.getValue()))
                .collect(Collectors.toList());

        return getArgumentCompleter(commandName, optionsDesc);
    }

    private static Completer getArgumentCompleter(String commandName, List<OptionAndCompleter> optionsDesc) {

        optionsDesc.add(new OptionAndCompleter("--help", NullCompleter.INSTANCE, OptionAndCompleter.Type.LONG_OPTION));
        optionsDesc.add(new OptionAndCompleter("-?", NullCompleter.INSTANCE, OptionAndCompleter.Type.SHORT_OPTION));

        List<String> optionsString = optionsDesc.stream()
                .filter(option -> option.type != OptionAndCompleter.Type.PRIMARY)
                .map(option -> option.option)
                .collect(Collectors.toList());

        List<Completers.OptDesc> optDescs = optionsDesc.stream().flatMap(option -> {
            switch (option.type) {
                case LONG_OPTION:
                    return Stream.of(new Completers.OptDesc(null, option.option, null, option.completer));
                case SHORT_OPTION:
                    return Stream.of(new Completers.OptDesc(option.option, null, null, option.completer));
                case PRIMARY:
                    return Stream.of();
            }
            throw new IllegalStateException(option.type.name());
        }).collect(Collectors.toList());

        Completer completerForPrimaryOption = optionsDesc.stream()
                .filter(option -> option.type == OptionAndCompleter.Type.PRIMARY)
                .map(option -> option.completer)
                .findFirst()
                .orElse(NullCompleter.INSTANCE);

        return new ArgumentCompleter(
                new StringsCompleter(commandName),
                new AggregateCompleter(new Completers.OptionCompleter(new StringsCompleter(optionsString), ignored -> optDescs, 1), completerForPrimaryOption));
    }

    static class OptionAndCompleter {
        final String option;
        final Completer completer;
        final Type type;

        public enum Type {
            LONG_OPTION,
            SHORT_OPTION,
            PRIMARY
        }

        OptionAndCompleter(String option, Completer completer, Type type) {
            this.option = option;
            this.completer = completer;
            this.type = type;
        }
    }

    private static Stream<OptionAndCompleter> toOptionAndCompleter(String parameterName, ParamModel option) {

        Param param = option.getParam();
        String optionName = !"".equals(param.name()) ? param.name() : parameterName;
        if (isInternalCommand(optionName)) {
            return Stream.of();
        }
        Stream.Builder<OptionAndCompleter> builder = Stream.builder();
        if (param.primary()) {
            builder.add(new OptionAndCompleter(optionName, getValueCompleter(option), OptionAndCompleter.Type.PRIMARY));
            return builder.build();
        }

        String shortName = param.shortName();
        String alias = param.alias();
        if (!"".equals(alias)) {
            builder.add(new OptionAndCompleter("--" + alias, getValueCompleter(option), OptionAndCompleter.Type.LONG_OPTION));
        }

        if (!"".equals(shortName)) {
            builder.add(new OptionAndCompleter("-" + shortName, getValueCompleter(option), OptionAndCompleter.Type.SHORT_OPTION));
        }

        builder.add(new OptionAndCompleter("--" + optionName, getValueCompleter(option),  OptionAndCompleter.Type.LONG_OPTION));

        return builder.build();
    }

    private static Completer getValueCompleter(ParamModel option) {
        if (option.getType() == File.class) {
            return new Completers.FileNameCompleter();
        }
        if (option.getType() == boolean.class || option.getType() == Boolean.class) {
            return new StringsCompleter("true", "false");
        }

        String acceptableValues = option.getParam().acceptableValues();
        if (!"".equals(acceptableValues)) {
            return new StringsCompleter(Arrays.stream(acceptableValues.split("" + option.getParam().separator()))
                    .map(String::trim)
                    .toArray(String[]::new));
        }
        // These options have String type and no other information available.
        // Searching for "dir" can return "redirect-port" and "file" can return "profile"
        if (List.of("nodedir",
                "installdir",
                "sshkeyfile",
                "sshpublickeyfile",
                "backupdir",
                "domaindir",
                "filename",
                "sqlfilename",
                "adminpasswordfile",
                "classpath",
                "nativelibrarypath",
                "logfile",
                "transactionlogdir"
        ).contains(option.getName())) {
            return new Completers.FileNameCompleter();
        }
        return (LineReader line, ParsedLine parsedLine, List<Candidate> candidates) -> {
            // For cases where we don't know which values would be valid, only return the
            // FileNameCompleter when the user tries to autocomplete a file-like value
            if (Stream.of(".", "..", "../", "~", "/", "..\\", "./")
                    .anyMatch(parsedLine.word()::startsWith)) {
                new Completers.FileNameCompleter().complete(line, parsedLine, candidates);
            }
        };
    }

    private static void atomicReplace(ServiceLocator locator, ProgramOptions options) {
        DynamicConfigurationService dcs = locator.getService(DynamicConfigurationService.class);
        DynamicConfiguration config = dcs.createDynamicConfiguration();

        config.addUnbindFilter(BuilderHelper.createContractFilter(ProgramOptions.class.getName()));
        ActiveDescriptor<ProgramOptions> desc = BuilderHelper.createConstantDescriptor(options, null, ProgramOptions.class);
        config.addActiveDescriptor(desc);

        config.commit();
    }

    interface Prompter {
        String prompt(String message) throws IOException;
        void close() throws IOException;
    }

    class BufferedReaderPrompter implements Prompter {
        private final BufferedReader reader;

        BufferedReaderPrompter(BufferedReader reader) {
            this.reader = reader;
        }

        @Override
        public String prompt(String message) throws IOException {
            if (printPrompt) {
                System.out.print(message);
                System.out.flush();
            }
            return reader.readLine();
        }

        public void close() {

        }
    }

    class JLinePrompter implements Prompter {

        private LineReader reader;
        private Terminal terminal;

        JLinePrompter() throws IOException{
            setupTerminal();
        }

        private void setupTerminal() throws IOException {
            terminal = TerminalBuilder.builder()
                    .system(true)
                    .build();
            SystemCompleter systemCompleter = new SystemCompleter();

            habitat.getDescriptors(descriptor -> descriptor.getAdvertisedContracts().contains("com.sun.enterprise.admin.cli.CLICommand"))
                    .forEach(activeDescriptor -> {
                        String commandName = activeDescriptor.getName();
                        if (isInternalCommand(commandName)) {
                            return;
                        }
                        try {
                            habitat.reifyDescriptor(activeDescriptor);
                            systemCompleter.add(commandName, getCompleterForLocalCommand(commandName, activeDescriptor.getImplementationClass()));
                        } catch (Exception ignored) {
                        }
                    });

            systemCompleter.add(List.of("exit", "quit"), NullCompleter.INSTANCE);
            systemCompleter.compile(Candidate::new);

            reader = LineReaderBuilder.builder()
                    .completer(new AggregateCompleter(systemCompleter, new RemoteCommandsCompleter()))
                    .variable(LineReader.HISTORY_FILE, Paths.get(System.getProperty("user.home"), ".gfclient", ".cli-history"))
                    .terminal(terminal).build();
        }

        @Override
        public String prompt(String message) throws IOException {
            if (terminal == null) {
                setupTerminal();
            }
            try {
                if (printPrompt) {
                    return reader.readLine(message);
                }
                return reader.readLine();
            } catch (UserInterruptException | EndOfFileException ignored) {
                return null;
            }
        }

        public void close() throws IOException {
            reader = null;
            terminal.close();
            terminal = null;
        }
    }

    /**
     * Read commands from the specified BufferedReader and execute them. If printPrompt is set, prompt first.
     *
     * @return the exit code of the last command executed
     */
    private int executeCommands(Prompter prompter) throws CommandException, CommandValidationException, IOException {
        String line;
        int rc = 0;

        /*
         * Any program options we start with are copied to the environment
         * to serve as defaults for commands we run, and then we give each
         * command an empty program options.
         */
        programOpts.toEnvironment(env);
        String prompt = programOpts.getCommandName() + "> ";
        for (;;) {
            if ((line = prompter.prompt(prompt)) == null) {
                if (printPrompt) {
                    System.out.println();
                }
                break;
            }

            if (line.trim().startsWith("#")) { // ignore comment lines
                continue;
            }

            String[] args;
            try {
                args = getArgs(line);
            } catch (ArgumentTokenizer.ArgumentException ex) {
                logger.info(ex.getMessage());
                continue;
            }

            if (args.length == 0) {
                continue;
            }

            String command = args[0];
            if (command.length() == 0) {
                continue;
            }

            // handle built-in exit and quit commands
            // XXX - care about their arguments?
            if (command.equals("exit") || command.equals("quit")) {
                break;
            }

            if (command.equals("osgi-shell")) {
                // Stop this JLine instance
                prompter.close();
            }

            CLICommand cmd = null;
            ProgramOptions po = null;
            try {
                /*
                 * Every command gets its own copy of program options
                 * so that any program options specified in its
                 * command line options don't effect other commands.
                 * But all commands share the same environment.
                 */
                po = new ProgramOptions(env);
                // copy over AsadminMain info
                po.setModulePath(programOpts.getModulePath());
                po.setClassPath(programOpts.getClassPath());
                po.setClassName(programOpts.getClassName());
                po.setCommandName(programOpts.getCommandName());
                // remove the old one and replace it
                atomicReplace(habitat, po);

                cmd = CLICommand.getCommand(habitat, command);
                rc = cmd.execute(args);
            } catch (CommandValidationException cve) {
                logger.severe(cve.getMessage());
                if (cmd != null) {
                    logger.severe(cmd.getUsage());
                }
                rc = ERROR;
            } catch (InvalidCommandException ice) {
                // find closest match with local or remote commands
                logger.severe(ice.getMessage());
                try {
                    if (po != null) {
                        // many layers below, null WILL be de-referenced.
                        CLIUtil.displayClosestMatch(command, CLIUtil.getAllCommands(container, po, env),
                            strings.get("ClosestMatchedLocalAndRemoteCommands"), logger);
                    }
                } catch (InvalidCommandException e) {
                    // not a big deal if we cannot help
                }
                rc = ERROR;
            } catch (CommandException ce) {
                if (ce.getCause() instanceof java.net.ConnectException) {
                    // find closest match with local commands
                    logger.severe(ce.getMessage());
                    try {
                        CLIUtil.displayClosestMatch(command, CLIUtil.getLocalCommands(container),
                                strings.get("ClosestMatchedLocalCommands"), logger);
                    } catch (InvalidCommandException e) {
                        logger.info(strings.get("InvalidRemoteCommand", command));
                    }
                } else {
                    logger.severe(ce.getMessage());
                }
                rc = ERROR;
            } finally {
                // restore the original program options
                // XXX - is this necessary?
                atomicReplace(habitat, programOpts);
            }

            // XXX - this duplicates code in AsadminMain, refactor it
            switch (rc) {
                case SUCCESS:
                    if (!programOpts.isTerse()) {
                        logger.fine(strings.get("CommandSuccessful", command));
                    }
                    break;

                case ERROR:
                case INVALID_COMMAND_ERROR:
                case CONNECTION_ERROR:
                default:
                    logger.fine(strings.get("CommandUnSuccessful", command));
                    break;
            }
            CLIUtil.writeCommandToDebugLog(programOpts.getCommandName() + "[multimode]", env, args, rc);
        }
        return rc;
    }

    private static boolean isInternalCommand(String commandName) {
        return commandName == null || commandName.startsWith("_");
    }

    private String[] getArgs(String line) throws ArgumentTokenizer.ArgumentException {
        List<String> args = new ArrayList<>();
        ArgumentTokenizer t = new ArgumentTokenizer(line);
        while (t.hasMoreTokens()) {
            args.add(t.nextToken());
        }
        return args.toArray(new String[args.size()]);
    }

    private class RemoteCommandsCompleter implements Completer {
        Completer cached;

        @Override
        public void complete(LineReader lineReader, ParsedLine parsedLine, List<Candidate> list) {
            try {
                if (cached == null) {

                    if (computeRemoteCommandsCache()) return;
                }
                cached.complete(lineReader, parsedLine, list);
            } catch(CommandException ignored){
            }
        }

        private boolean computeRemoteCommandsCache() throws CommandException {
            String[] remoteCommands = CLIUtil.getRemoteCommands(container, programOpts, env);
            if (remoteCommands == null) {
                return true;
            }

            SystemCompleter systemCompleter = new SystemCompleter();

            for (String remoteCommand : remoteCommands) {
                if (isInternalCommand(remoteCommand)) {
                    continue;
                }
                List<OptionAndCompleter> options = getOptionsForCommand(remoteCommand).getParameters()
                        .stream()
                        .flatMap(parameter -> toOptionAndCompleter(parameter.getName(), parameter))
                        .collect(Collectors.toList());
                Completer argumentCompleter = getArgumentCompleter(remoteCommand, options);
                systemCompleter.add(remoteCommand, argumentCompleter);
            }

            systemCompleter.compile(Candidate::new);
            cached = systemCompleter;
            return false;
        }

        private CommandModel getOptionsForCommand(String name1)  {

            try {
                var remoteCLICommand = new RemoteCLICommand(name1, MultimodeCommand.this.programOpts, MultimodeCommand.this.env) {
                    public CommandModel getCommandModel() throws CommandException {
                        argv = new String[]{this.name};
                        prepare();
                        parse();
                        return this.commandModel;
                    }
                };
                return remoteCLICommand.getCommandModel();
            } catch (CommandException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
