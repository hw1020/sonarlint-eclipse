/*
 * SonarLint for Eclipse
 * Copyright (C) 2015-2020 SonarSource SA
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarlint.eclipse.core.internal.telemetry;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.annotation.Nullable;
import org.osgi.framework.Bundle;
import org.sonarlint.eclipse.core.SonarLintLogger;
import org.sonarlint.eclipse.core.internal.SonarLintCorePlugin;
import org.sonarlint.eclipse.core.internal.engine.connected.IConnectedEngineFacade;
import org.sonarlint.eclipse.core.internal.engine.connected.ResolvedBinding;
import org.sonarlint.eclipse.core.internal.resources.ProjectsProviderUtils;
import org.sonarlint.eclipse.core.internal.utils.SonarLintUtils;
import org.sonarsource.sonarlint.core.client.api.common.TelemetryClientConfig;
import org.sonarsource.sonarlint.core.telemetry.TelemetryClient;
import org.sonarsource.sonarlint.core.telemetry.TelemetryManager;
import org.sonarsource.sonarlint.core.telemetry.TelemetryPathManager;

public class SonarLintTelemetry {
  private static final String TELEMETRY_PRODUCT_KEY = "eclipse";
  private static final String PRODUCT = "SonarLint Eclipse";
  private static final String OLD_STORAGE_FILENAME = "sonarlint_usage";
  public static final String DISABLE_PROPERTY_KEY = "sonarlint.telemetry.disabled";

  private TelemetryManager telemetry;

  private TelemetryJob scheduledJob;

  static Path getStorageFilePath() {
    TelemetryPathManager.migrate(TELEMETRY_PRODUCT_KEY, getOldStorageFilePath());
    return TelemetryPathManager.getPath(TELEMETRY_PRODUCT_KEY);
  }

  private static Path getOldStorageFilePath() {
    return SonarLintCorePlugin.getInstance().getStateLocation().toFile().toPath().resolve(OLD_STORAGE_FILENAME);
  }

  public static boolean shouldBeActivated() {
    return !"true".equals(System.getProperty(DISABLE_PROPERTY_KEY));
  }

  public void optOut(boolean optOut) {
    if (telemetry != null) {
      if (optOut) {
        if (telemetry.isEnabled()) {
          telemetry.disable();
        }
      } else {
        if (!telemetry.isEnabled()) {
          telemetry.enable();
        }
      }
    }
  }

  public boolean enabled() {
    return telemetry != null && telemetry.isEnabled();
  }

  public void init() {
    try {
      TelemetryClientConfig clientConfig = getTelemetryClientConfig();
      TelemetryClient client = new TelemetryClient(clientConfig, PRODUCT, SonarLintUtils.getPluginVersion(), ideVersionForTelemetry());
      this.telemetry = newTelemetryManager(getStorageFilePath(), client);
      this.scheduledJob = new TelemetryJob();
      scheduledJob.schedule(TimeUnit.MINUTES.toMillis(1));
    } catch (Exception e) {
      if (org.sonarsource.sonarlint.core.client.api.util.SonarLintUtils.isInternalDebugEnabled()) {
        SonarLintLogger.get().error("Failed during periodic telemetry job", e);
      }
    }
  }

  private static String ideVersionForTelemetry() {
    StringBuilder sb = new StringBuilder();
    IProduct iProduct = Platform.getProduct();
    if (iProduct != null) {
      sb.append(iProduct.getName());
    } else {
      sb.append("Unknown");
    }
    Bundle platformBundle = Platform.getBundle("org.eclipse.platform");
    if (platformBundle != null) {
      sb.append(" ");
      sb.append(platformBundle.getVersion());
    }
    return sb.toString();
  }

  // visible for testing
  public TelemetryManager newTelemetryManager(Path path, TelemetryClient client) {
    return new TelemetryManager(path, client, SonarLintTelemetry::isAnyOpenProjectBound, SonarLintTelemetry::isAnyOpenProjectBoundToSonarCloud);
  }

  private class TelemetryJob extends Job {

    public TelemetryJob() {
      super("SonarLint Telemetry");
      setSystem(true);
    }

    protected IStatus run(IProgressMonitor monitor) {
      schedule(TimeUnit.HOURS.toMillis(6));
      upload();
      return Status.OK_STATUS;
    }

  }

  public static TelemetryClientConfig getTelemetryClientConfig() {
    TelemetryClientConfig.Builder clientConfigBuilder = new TelemetryClientConfig.Builder()
      .userAgent("SonarLint");

    SonarLintUtils.configureProxy(TelemetryManager.TELEMETRY_ENDPOINT, clientConfigBuilder::proxy, (user, pwd) -> {
      clientConfigBuilder.proxyLogin(user);
      clientConfigBuilder.proxyPassword(pwd);
    });

    return clientConfigBuilder.build();
  }

  // visible for testing
  public void upload() {
    if (enabled()) {
      telemetry.uploadLazily();
    }
  }

  public void analysisDoneOnMultipleFiles() {
    if (enabled()) {
      telemetry.analysisDoneOnMultipleFiles();
    }
  }

  public void analysisDoneOnSingleFile(@Nullable String language, int time) {
    if (enabled()) {
      telemetry.analysisDoneOnSingleLanguage(language, time);
    }
  }

  public void stop() {
    if (scheduledJob != null) {
      scheduledJob.cancel();
      scheduledJob = null;
    }
    if (enabled()) {
      telemetry.stop();
    }
  }

  // visible for testing
  public Job getScheduledJob() {
    return scheduledJob;
  }

  public static boolean isAnyOpenProjectBound() {
    return ProjectsProviderUtils.allProjects().stream()
        .anyMatch(p -> p.isOpen() && SonarLintCorePlugin.loadConfig(p).isBound());
  }

  public static boolean isAnyOpenProjectBoundToSonarCloud() {
    return ProjectsProviderUtils.allProjects().stream()
      .filter(p -> p.isOpen() && SonarLintCorePlugin.loadConfig(p).isBound())
      .map(SonarLintCorePlugin.getServersManager()::resolveBinding)
      .filter(Optional::isPresent)
      .map(Optional::get)
      .map(ResolvedBinding::getEngineFacade)
      .anyMatch(IConnectedEngineFacade::isSonarCloud);
  }

}
