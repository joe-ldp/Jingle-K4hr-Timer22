package me.ravalle.k4hrtimer;

import com.google.common.io.Resources;
import me.ravalle.k4hrtimer.gui.K4hrTimerPanel;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.Level;
import xyz.duncanruns.jingle.Jingle;
import xyz.duncanruns.jingle.JingleAppLaunch;
import xyz.duncanruns.jingle.gui.JingleGUI;
import xyz.duncanruns.jingle.plugin.PluginManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class K4hrTimer {
    public static String VERSION = "DEV";
    public static final Path K4HR_TIMER_FOLDER_PATH = Jingle.FOLDER.resolve("k4hr-timer-plugin");
    public static final Path K4HR_TIMER_SETTINGS_PATH = K4HR_TIMER_FOLDER_PATH.resolve("settings.json");
    public static String CURRENT_ATTEMPT_PATH = K4HR_TIMER_FOLDER_PATH.resolve("currentAttempt.txt").toString();

    public static void main(String[] args) throws IOException {
        // This is only used to test the plugin in the dev environment
        // K4hrTimer.main itself is never used when users run Jingle

        JingleAppLaunch.launchWithDevPlugin(args, PluginManager.JinglePluginData.fromString(
                Resources.toString(Resources.getResource(K4hrTimer.class, "/jingle.plugin.json"), Charset.defaultCharset())
        ), K4hrTimer::initialize);
    }

    public static void initialize() {
        VERSION = PluginManager.getLoadedPlugins().stream().map(p -> p.pluginData).filter(d -> d.id.equals("jingle-k4hr-timer-plugin")).map(d -> d.version).findFirst().orElse("Unknown");

        boolean isFirstLaunch = !K4HR_TIMER_FOLDER_PATH.toFile().exists();
        if (isFirstLaunch) {
            if (!K4HR_TIMER_FOLDER_PATH.toFile().mkdirs()) {
                Jingle.log(Level.ERROR, "(K4hrTimerPlugin) Unable to create plugin folder! Plugin will terminate.");
                return;
            }
        }

        K4hrTimerSettings.load();

        JPanel k4hrTimerPanel = new K4hrTimerPanel().mainPanel;
        JingleGUI.addPluginTab("K4hr Timer", k4hrTimerPanel);

        JingleGUI.get().registerQuickActionButton(10000, () -> JingleGUI.makeButton("Reset K4hr Attempt (DELETES ALL RECORDS)",
                K4hrTimer::bopAllRecords,
                () -> JingleGUI.get().openTab(k4hrTimerPanel),
                "Right Click to Configure",
                true));

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                List<Run> currentAttempt = getCurrentAttempt();
                if (!currentAttempt.isEmpty()) {
                    outputAttempt(currentAttempt);
                } else {
                    try {
                        Files.write(Path.of(CURRENT_ATTEMPT_PATH), "".getBytes());
                    } catch (IOException e) {
                        Jingle.logError(e.getMessage(), e);
                    }
                }
            }
        }, 0, 30000);
    }

    public static List<Run> getCurrentAttempt() {
        Stream<File> records = Stream.of(Objects.requireNonNull(new File(K4hrTimerSettings.getInstance().recordsPath).listFiles()))
                .filter(file -> file.lastModified() >= System.currentTimeMillis() - TimeUnit.HOURS.toMillis(4));

        ObjectMapper objectMapper = new ObjectMapper();
        records = records.filter(file -> {
            try {
                JsonNode rootNode = objectMapper.readTree(file);
                return Objects.equals(rootNode.get("is_completed").asText(), "true") && (rootNode.get("date").asLong() - rootNode.get("final_rta").asLong() >= System.currentTimeMillis() - TimeUnit.HOURS.toMillis(4));
            } catch (IOException e) {
                Jingle.logError(e.getMessage(), e);
                return false;
            }
        });

        return records.map(file -> {
            try {
                JsonNode rootNode = objectMapper.readTree(file);
                return new Run(
                        rootNode.get("final_rta").asLong(),
                        rootNode.get("retimed_igt").asLong(),
                        rootNode.get("date").asLong() - rootNode.get("final_rta").asLong(),
                        rootNode.get("date").asLong());
            } catch (IOException e) {
                Jingle.logError(e.getMessage(), e);
                return null;
            }
        })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingLong(Run::getCompletedAt))
            .collect(Collectors.toList());
    }

    public static void outputAttempt(List<Run> attempt) {
        try {
            FileWriter fw = new FileWriter(CURRENT_ATTEMPT_PATH);
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(String.format("Runs: %s | Time Remaining: %s | Est. Pace: %d%n", attempt.size(), DurationFormatUtils.formatDuration(getTimeRemaining(attempt), "H':'m':'s"), getEstimatedPace(attempt)));
            AtomicInteger count = new AtomicInteger();
            attempt.forEach(run -> {
                try {
                    bw.write(String.format("#%d: %s%s",
                            attempt.indexOf(run) + 1, // run number
                            run.toString(), // run time
                            (count.incrementAndGet() % 2 == 0) ? System.lineSeparator() : "\t")); // newline if even # run, tab if odd. compacts graphic on screen
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            bw.close();
            fw.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void bopAllRecords() {
        List<File> records = Stream.of(Objects.requireNonNull(new File(K4hrTimerSettings.getInstance().recordsPath).listFiles())).toList();

        int total = records.size(); // shamelessly ripped Jingle Bopping code
        if (records.isEmpty()) {
            return;
        }
        AtomicInteger cleared = new AtomicInteger();
        records.parallelStream().forEach(file -> {
            try {
                Files.walk(file.toPath())
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            } catch (IOException e) {
                Jingle.logError("Failed to delete record \"" + file + "\".", e);
            }
            if (cleared.incrementAndGet() % 50 == 0) {
                Thread.currentThread().setName("record-bopper");
                Jingle.log(Level.INFO, String.format("Cleared %d/%d records", cleared.get(), total));
            }
        });
        Jingle.log(Level.INFO, String.format("Cleared %d records", total));
    }

    public static long getRunElapsedTime(List<Run> attempt, int runNo) {
        return attempt.get(runNo).getRta() + (runNo > 1 ? (attempt.get(runNo - 1).getCompletedAt() - attempt.get(runNo).getStartedAt()) : 0);
    }

    public static long getAverageElapsedTime(List<Run> attempt) {
        return IntStream.range(0, attempt.size())
                .mapToLong(i -> getRunElapsedTime(attempt, i))
                .sum() / attempt.size();
    }

    public static int getEstimatedPace(List<Run> attempt) {
        return (int) (getTimeRemaining(attempt) / getAverageElapsedTime(attempt));
    }

    public static long getTimeRemaining(List<Run> attempt) {
        return (getAttemptStart(attempt) + TimeUnit.HOURS.toMillis(4)) - System.currentTimeMillis();
    }

    public static long getAttemptStart(List<Run> attempt) {
        return attempt.getFirst().getStartedAt();
    }
}

