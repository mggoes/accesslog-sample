package br.com.sample;

import static java.lang.Boolean.TRUE;
import static java.lang.Runtime.getRuntime;
import static java.util.Calendar.DAY_OF_MONTH;
import static java.util.Calendar.HOUR_OF_DAY;
import static java.util.Calendar.MILLISECOND;
import static java.util.Calendar.MINUTE;
import static java.util.Calendar.SECOND;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.springframework.util.Assert.isTrue;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.Undertow.Accesslog;
import org.springframework.boot.context.embedded.ConfigurableEmbeddedServletContainer;
import org.springframework.boot.context.embedded.EmbeddedServletContainerCustomizer;
import org.springframework.boot.context.embedded.undertow.UndertowDeploymentInfoCustomizer;
import org.springframework.boot.context.embedded.undertow.UndertowEmbeddedServletContainerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import br.com.sample.AccessLogSampleApplication.PurgeProperties;
import io.undertow.servlet.api.DeploymentInfo;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * The type Accesslog sample application.
 */
@SpringBootApplication
@EnableConfigurationProperties(PurgeProperties.class)
public class AccessLogSampleApplication {

	/**
	 * Main.
	 *
	 * @param args the args
	 */
	public static void main(final String... args) {
		SpringApplication.run(AccessLogSampleApplication.class, args);
	}

	/**
	 * The type Purge properties.
	 */
	@Data
	@ConfigurationProperties("accesslog.purge")
	public static class PurgeProperties implements InitializingBean {

		/**
		 * The constant ALLOWED_UNITS.
		 */
		private static final EnumSet<TimeUnit> ALLOWED_UNITS = EnumSet.of(SECONDS, MINUTES, HOURS, DAYS);

		/**
		 * The Enabled.
		 */
		private boolean enabled;
		/**
		 * The Execute on startup.
		 */
		private boolean executeOnStartup;
		/**
		 * The Execution interval.
		 */
		private long executionInterval = 24;
		/**
		 * The Max history.
		 */
		private long maxHistory = 30;
		/**
		 * The Execution interval unit.
		 */
		private TimeUnit executionIntervalUnit = HOURS;
		/**
		 * The Max history unit.
		 */
		private TimeUnit maxHistoryUnit = DAYS;

		/**
		 * After properties set.
		 *
		 * @throws Exception the exception
		 */
		@Override
		public void afterPropertiesSet() throws Exception {
			isTrue(this.executionInterval > 0, "'executionInterval' must be greater than 0");
			isTrue(this.maxHistory > 0, "'maxHistory' must be greater than 0");
			isTrue(ALLOWED_UNITS.contains(this.executionIntervalUnit), "'executionIntervalUnit' must be one of the following units: SECONDS, MINUTES, HOURS, DAYS");
			isTrue(ALLOWED_UNITS.contains(this.maxHistoryUnit), "'maxHistoryUnit' must be one of the following units: SECONDS, MINUTES, HOURS, DAYS");
		}
	}

	/**
	 * The type Purgable accesslog config.
	 */
	@Configuration
	@AllArgsConstructor
	public class PurgeableAccessLogConfig implements EmbeddedServletContainerCustomizer {

		/**
		 * The Server properties.
		 */
		private final ServerProperties serverProperties;
		/**
		 * The Purge properties.
		 */
		private final PurgeProperties purgeProperties;

		/**
		 * Customize.
		 *
		 * @param container the container
		 */
		@Override
		public void customize(final ConfigurableEmbeddedServletContainer container) {
			final UndertowEmbeddedServletContainerFactory factory = (UndertowEmbeddedServletContainerFactory) container;
			final Accesslog accesslog = this.serverProperties.getUndertow().getAccesslog();
			if (accesslog != null && TRUE.equals(accesslog.getEnabled()) && this.purgeProperties.isEnabled()) {
				factory.addDeploymentInfoCustomizers(new PurgeableAccessLogDeploymentInfoCustomizer(this.purgeProperties, accesslog));
			}
		}
	}

	/**
	 * The type Purgeable access log deployment info customizer.
	 */
	@AllArgsConstructor
	public static class PurgeableAccessLogDeploymentInfoCustomizer implements UndertowDeploymentInfoCustomizer {

		/**
		 * The Purge properties.
		 */
		private final PurgeProperties purgeProperties;
		/**
		 * The Accesslog.
		 */
		private final Accesslog accesslog;

		/**
		 * Customize.
		 *
		 * @param deploymentInfo the deployment info
		 */
		@Override
		public void customize(final DeploymentInfo deploymentInfo) {
			long initialDelay = 0;

			if (!this.purgeProperties.isExecuteOnStartup()) {
				final Calendar baseDate = Calendar.getInstance();
				baseDate.set(HOUR_OF_DAY, 0);
				baseDate.set(MINUTE, 0);
				baseDate.set(SECOND, 0);
				baseDate.set(MILLISECOND, 0);
				baseDate.add(DAY_OF_MONTH, 1);

				final long midnight = baseDate.getTimeInMillis();
				final long now = new Date().getTime();

				initialDelay = midnight - now;
			}

			final PurgeTask purgeTask = new PurgeTask(this.purgeProperties, this.accesslog);
			final long executionInterval = this.purgeProperties.getExecutionInterval();
			final TimeUnit executionIntervalUnit = this.purgeProperties.getExecutionIntervalUnit();

			Executors.newScheduledThreadPool(getRuntime().availableProcessors()).scheduleWithFixedDelay(purgeTask, initialDelay, executionInterval, executionIntervalUnit);
		}

	}

	/**
	 * The type Purge task.
	 */
	@Slf4j
	public static class PurgeTask implements Runnable {

		/**
		 * The constant ANY_CHARACTER_PATTERN.
		 */
		private static final String ANY_CHARACTER_PATTERN = ".*";
		/**
		 * The Purge properties.
		 */
		private final PurgeProperties purgeProperties;
		/**
		 * The Access log dir.
		 */
		private final File accessLogDir;
		/**
		 * The Current log file name.
		 */
		private final String currentLogFileName;
		/**
		 * The Pattern.
		 */
		private final String pattern;

		/**
		 * Instantiates a new Purge task.
		 *
		 * @param purgeProperties the purge properties
		 * @param accesslog       the accesslog
		 */
		public PurgeTask(final PurgeProperties purgeProperties, final Accesslog accesslog) {
			this.purgeProperties = purgeProperties;
			this.accessLogDir = accesslog.getDir();
			this.currentLogFileName = accesslog.getPrefix() + accesslog.getSuffix();
			this.pattern = this.buildPattern(accesslog);
		}

		/**
		 * Run.
		 */
		@Override
		public void run() {
			log.trace("Purging access log files...");
			final File[] files = this.accessLogDir.listFiles(new FilenameFilter() {
				@Override
				public boolean accept(final File dir, final String name) {
					return isPurgeable(dir, name);
				}
			});
			if (files != null) {
				for (final File file : files) {
					this.purge(file);
				}
			}
			log.trace("Purging finished!");
		}

		private boolean isPurgeable(final File file, final String fileName) {
			boolean purgeable = false;
			log.trace("File name: {}", fileName);

			if (!this.currentLogFileName.equals(fileName) && fileName.matches(this.pattern)) {
				final TimeUnit maxHistoryUnit = this.purgeProperties.getMaxHistoryUnit();

				final long lastModified = maxHistoryUnit.convert(file.lastModified(), MILLISECONDS);
				log.trace("Last modified: {}", lastModified);

				final long now = maxHistoryUnit.convert(new Date().getTime(), MILLISECONDS);
				log.trace("Now: {}", now);

				final long between = now - lastModified;
				log.trace("Between: {} {}", between, maxHistoryUnit);

				purgeable = between > this.purgeProperties.getMaxHistory();
			}

			log.trace("Purgeable: {}", purgeable);
			return purgeable;
		}

		private void purge(final File accessLogFile) {
			try {
				final boolean deleted = accessLogFile.delete();
				log.trace("Deleted: {}", deleted);
			} catch (final SecurityException e) {
				log.error(e.getMessage(), e);
			}
		}

		/**
		 * Build pattern string.
		 *
		 * @param accesslog the accesslog
		 * @return the string
		 */
		private String buildPattern(final Accesslog accesslog) {
			return new StringBuilder()
					.append(this.normalizeDotCharacter(accesslog.getPrefix()))
					.append(ANY_CHARACTER_PATTERN)
					.append(this.normalizeDotCharacter(accesslog.getSuffix()))
					.append(ANY_CHARACTER_PATTERN)
					.toString();
		}

		/**
		 * Normalize dot character string.
		 *
		 * @param text the text
		 * @return the string
		 */
		private String normalizeDotCharacter(final String text) {
			return text.replace(".", "\\.");
		}
	}
}
