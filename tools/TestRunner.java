/*
 * Skirmish — an escape tool and a combat lock for Minecraft servers.
 * Copyright (C) 2026 Alex Fernandez
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 */
// A ~40-line JUnit runner, because junit-platform-console-standalone is not vendored and the
// server whose gradle cache held it is being decommissioned. Uses junit-platform-launcher,
// which IS vendored. Build-time only: never shipped inside the plugin jar.
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import java.io.PrintWriter;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

public final class TestRunner {
    public static void main(String[] args) {
        String pkg = args.length > 0 ? args[0] : "app.vexoris.skirmish";
        LauncherDiscoveryRequest req = request().selectors(selectPackage(pkg)).build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(req, listener);
        TestExecutionSummary s = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out);
        s.printTo(out);
        if (s.getTotalFailureCount() > 0) s.printFailuresTo(out, 10);
        out.flush();
        System.out.printf("  tests: %d  passed: %d  FAILED: %d%n",
                s.getTestsStartedCount(), s.getTestsSucceededCount(), s.getTotalFailureCount());
        System.exit(s.getTotalFailureCount() > 0 ? 1 : 0);
    }
}
