package org.embulk.exec;

import java.util.List;
import java.util.ArrayList;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.common.base.Throwables;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.NextConfig;
import org.embulk.config.DataSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.config.ConfigSource;
import org.embulk.config.CommitReport;
import org.embulk.plugin.PluginType;
import org.embulk.spi.Schema;
import org.embulk.spi.Column;
import org.embulk.spi.Page;
import org.embulk.spi.Buffer;
import org.embulk.spi.InputPlugin;
import org.embulk.spi.FileInputPlugin;
import org.embulk.spi.ParserPlugin;
import org.embulk.spi.GuessPlugin;
import org.embulk.spi.Exec;
import org.embulk.spi.ExecAction;
import org.embulk.spi.ExecSession;
import org.embulk.spi.FileInput;
import org.embulk.spi.PageOutput;
import org.embulk.spi.TransactionalFileInput;
import org.embulk.spi.FileInputRunner;

public class GuessExecutor
{
    private final Injector injector;
    private final ConfigSource systemConfig;
    private final List<PluginType> defaultGuessPlugins;

    private interface GuessExecutorTask
            extends Task
    {
        @Config("guess_plugins")
        @ConfigDefault("[]")
        public List<PluginType> getGuessPlugins();

        @Config("exclude_guess_plugins")
        @ConfigDefault("[]")
        public List<PluginType> getExcludeGuessPlugins();
    }

    @Inject
    public GuessExecutor(Injector injector,
            @ForSystemConfig ConfigSource systemConfig)
    {
        this.injector = injector;
        this.systemConfig = systemConfig;

        // TODO get default guess plugins from injector using Multibinder
        this.defaultGuessPlugins = ImmutableList.of(
                new PluginType("gzip"),
                new PluginType("charset"),
                new PluginType("newline"),
                new PluginType("csv"));
    }

    public NextConfig guess(ExecSession exec, final ConfigSource config)
    {
        try {
            return Exec.doWith(exec, new ExecAction<NextConfig>() {
                public NextConfig run()
                {
                    return doGuess(config);
                }
            });
        } catch (Exception ex) {
            throw Throwables.propagate(ex);
        }
    }

    private NextConfig doGuess(ConfigSource config)
    {
        Buffer sample = SamplingParserPlugin.runFileInputSampling(config);
        if (sample.limit() == 0) {
            throw new NoSampleException("Can't get sample data because the first input file is empty");
        }

        List<PluginType> guessPlugins = new ArrayList<PluginType>(defaultGuessPlugins);
        GuessExecutorTask task = config.getNestedOrSetEmpty("exec").loadConfig(GuessExecutorTask.class);
        guessPlugins.addAll(task.getGuessPlugins());
        guessPlugins.removeAll(task.getExcludeGuessPlugins());

        return runGuessInput(sample, config, guessPlugins);
    }

    private NextConfig runGuessInput(Buffer sample,
            ConfigSource config, List<PluginType> guessPlugins)
    {
        // repeat guessing upto 10 times
        NextConfig lastGuessed = Exec.newNextConfig();
        for (int i=0; i < 10; i++) {
            // include last-guessed config to run guess input
            ConfigSource originalConfig = config.getNested("in").deepCopy().merge(lastGuessed);
            ConfigSource guessInputConfig = originalConfig.deepCopy();
            guessInputConfig.getNestedOrSetEmpty("parser")
                .set("type", "system_guess")  // override in.parser.type so that FileInputPlugin creates GuessParserPlugin
                .set("guess_plugins", guessPlugins)
                .set("orig_config", originalConfig);

            // run FileInputPlugin
            final FileInputRunner input = new FileInputRunner(new BufferFileInputPlugin(sample));
            NextConfig guessed;
            try {
                input.transaction(guessInputConfig, new InputPlugin.Control() {
                    public List<CommitReport> run(TaskSource inputTaskSource, Schema schema, int processorCount)
                    {
                        // TODO repeat runwith processorIndex++ if NoSampleException happens
                        input.run(inputTaskSource, null, 0, new PageOutput() {
                            @Override
                            public void add(Page page)
                            {
                                throw new RuntimeException("Input plugin must be a FileInputPlugin to guess parser configuration");  // TODO exception class
                            }

                            @Override
                            public void finish() { }

                            @Override
                            public void close() { }
                        });
                        throw new AssertionError("Guess executor must throw GuessedNoticeError");
                    }
                });
                throw new AssertionError("Guess executor must throw GuessedNoticeError");

            } catch (GuessedNoticeError error) {
                guessed = lastGuessed.deepCopy().merge(error.getGuessedConfig());
            }

            // merge to the last-guessed config
            if (lastGuessed.equals(guessed)) {
                // not changed
                return wrapInIn(lastGuessed);
            }
            lastGuessed = guessed;
        }

        return wrapInIn(lastGuessed);
    }

    private static NextConfig wrapInIn(NextConfig lastGuessed)
    {
        NextConfig wrapped = Exec.newNextConfig();
        wrapped.getNestedOrSetEmpty("in").merge(lastGuessed);
        return wrapped;
    }

    private static class BufferFileInputPlugin
            implements FileInputPlugin
    {
        private final Buffer buffer;

        public BufferFileInputPlugin(Buffer buffer)
        {
            this.buffer = buffer;
        }

        public NextConfig transaction(ConfigSource config, FileInputPlugin.Control control)
        {
            control.run(Exec.newTaskSource(), 1);
            return Exec.newNextConfig();
        }

        public TransactionalFileInput open(TaskSource taskSource, int processorIndex)
        {
            return new BufferTransactionalFileInput(buffer);
        }
    }

    private static class BufferTransactionalFileInput
            implements TransactionalFileInput
    {
        private Buffer buffer;

        public BufferTransactionalFileInput(Buffer buffer)
        {
            this.buffer = buffer;
        }

        @Override
        public Buffer poll()
        {
            Buffer b = buffer;
            buffer = null;
            return b;
        }

        @Override
        public boolean nextFile()
        {
            return buffer != null;
        }

        @Override
        public void close() { }

        @Override
        public void abort() { }

        @Override
        public CommitReport commit()
        {
            return null;
        }
    }

    public static class GuessParserPlugin
            implements ParserPlugin
    {
        private interface PluginTask
                extends Task
        {
            @Config("guess_plugins")
            public List<PluginType> getGuessPluginTypes();

            @Config("orig_config")
            public ConfigSource getOriginalConfig();
        }

        @Override
        public void transaction(ConfigSource config, ParserPlugin.Control control)
        {
            PluginTask task = config.loadConfig(PluginTask.class);
            control.run(task.dump(), null);
        }

        @Override
        public void run(TaskSource taskSource, Schema schema,
                FileInput input, PageOutput pageOutput)
        {
            PluginTask task = taskSource.loadTask(PluginTask.class);
            final ConfigSource originalConfig = task.getOriginalConfig();

            // get sample buffer
            Buffer sample = getFirstBuffer(input);

            // load guess plugins
            ImmutableList.Builder<GuessPlugin> builder = ImmutableList.builder();
            for (PluginType guessType : task.getGuessPluginTypes()) {
                GuessPlugin guess = Exec.newPlugin(GuessPlugin.class, guessType);
                builder.add(guess);
            }
            List<GuessPlugin> guesses = builder.build();

            // run guess plugins
            ConfigSource mergedConfig = originalConfig.deepCopy();
            NextConfig mergedGuessed = Exec.newNextConfig();
            for (int i=0; i < guesses.size(); i++) {
                NextConfig guessed = guesses.get(i).guess(originalConfig, sample);
                mergedGuessed.merge(guessed);
                mergedConfig.merge(mergedGuessed);
                if (!mergedConfig.equals(originalConfig)) {
                    // config updated
                    throw new GuessedNoticeError(mergedGuessed);
                }
            }
            throw new GuessedNoticeError(mergedGuessed);
        }

        private static Buffer getFirstBuffer(FileInput input)
        {
            RuntimeException decodeException = null;
            try {
                while (input.nextFile()) {
                    Buffer sample = input.poll();
                    if (sample != null) {
                        return sample;
                    }
                }
            } catch (RuntimeException ex) {
                // ignores exceptions because FileDecoderPlugin can throw exceptions
                // such as "Unexpected end of ZLIB input stream"
                decodeException = ex;
            }
            if (decodeException != null) {
                throw decodeException;
            }
            throw new NoSampleException("No input buffer to guess");
        }
    }

    public static class GuessedNoticeError
            extends Error
    {
        private final NextConfig guessedConfig;

        public GuessedNoticeError(NextConfig guessedConfig)
        {
            this.guessedConfig = guessedConfig;
        }

        public NextConfig getGuessedConfig()
        {
            return guessedConfig;
        }
    }
}
