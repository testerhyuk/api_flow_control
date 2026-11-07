package com.hyuk.integration_test.support;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Docker {

    private Docker() {
    }

    public static String run(String... args) {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(args));

        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException(String.join(" ", command) + " 실패 (" + exitCode + ") : " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(String.join(" ", command) + " 실행 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("명령 실행 중 중단되었습니다", e);
        }
    }
}
