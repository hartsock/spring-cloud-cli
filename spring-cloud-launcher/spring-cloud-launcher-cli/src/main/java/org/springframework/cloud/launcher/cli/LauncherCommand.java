/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.launcher.cli;

import java.io.File;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.springframework.boot.cli.command.HelpExample;
import org.springframework.boot.cli.command.OptionParsingCommand;
import org.springframework.boot.cli.command.options.OptionHandler;
import org.springframework.boot.cli.command.status.ExitStatus;
import org.springframework.boot.cli.compiler.RepositoryConfigurationFactory;
import org.springframework.boot.cli.compiler.grape.AetherGrapeEngine;
import org.springframework.boot.cli.compiler.grape.AetherGrapeEngineFactory;
import org.springframework.boot.cli.compiler.grape.DependencyResolutionContext;
import org.springframework.boot.cli.compiler.grape.RepositoryConfiguration;
import org.springframework.util.StringUtils;

import groovy.lang.GroovyClassLoader;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

/**
 * @author Spencer Gibb
 */
public class LauncherCommand extends OptionParsingCommand {

	public static final Log log = LogFactory.getLog(LauncherCommand.class);

	private static final String DEFAULT_VERSION = "1.2.0.BUILD-SNAPSHOT";

	private static final Collection<HelpExample> EXAMPLES = new ArrayList<>();

	static {
		EXAMPLES.add(new HelpExample("Launch Eureka", "spring cloud eureka"));
		EXAMPLES.add(new HelpExample("Launch Config Server and Eureka", "spring cloud configserver eureka"));
		EXAMPLES.add(new HelpExample("List deployable apps", "spring cloud --list"));
	}

	public LauncherCommand() {
		super("cloud", "Start Spring Cloud services, like Eureka, Config Server, etc.", new LauncherOptionHandler());
	}

	@Override
	public Collection<HelpExample> getExamples() {
		return EXAMPLES;
	}

	private static class LauncherOptionHandler extends OptionHandler {

		private OptionSpec<Void> debugOption;
		private OptionSpec<Void> listOption;

		@Override
		protected void options() {
			this.debugOption = option(Arrays.asList("debug", "d"), "Debug logging for the deployer");
			this.listOption = option(Arrays.asList("list", "l"), "List the deployables (don't launch anything)");
		}

		@Override
		protected synchronized ExitStatus run(OptionSet options) throws Exception {

			try {
				URLClassLoader classLoader = populateClassloader();

				String name = "org.springframework.cloud.launcher.deployer.DeployerThread";
				Class<?> threadClass = classLoader.loadClass(name);

				Constructor<?> constructor = threadClass.getConstructor(ClassLoader.class, String[].class);
				Thread thread = (Thread) constructor.newInstance(classLoader, getArgs(options));
				thread.start();
				thread.join();
			}
			catch (Exception e) {
				e.printStackTrace();
			}

			return ExitStatus.OK;
		}

		private String[] getArgs(OptionSet options) {
			List<Object> args = new ArrayList<>();
			List<String> apps = new ArrayList<>();
			int sourceArgCount = 0;
			for (Object option : options.nonOptionArguments()) {
				if (option instanceof String) {
					String filename = (String) option;
					if ("--".equals(filename)) {
						break;
					}
					sourceArgCount++;
					apps.add(option.toString());
				}
			}
			if (options.has(this.debugOption)) {
				args.add("--debug=true");
			}
			if (options.has(this.listOption)) {
				args.add("--launcher.list=true");
			}
			else {
				if (!apps.isEmpty()) {
					args.add("--launcher.deploy=" + StringUtils.collectionToCommaDelimitedString(apps));
				}
			}
			args.addAll(options.nonOptionArguments().subList(sourceArgCount, options.nonOptionArguments().size()));
			return args.toArray(new String[args.size()]);
		}

		URLClassLoader populateClassloader() throws MalformedURLException {
			DependencyResolutionContext resolutionContext = new DependencyResolutionContext();

			GroovyClassLoader loader = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(),
					new CompilerConfiguration());

			List<RepositoryConfiguration> repositoryConfiguration = RepositoryConfigurationFactory
					.createDefaultRepositoryConfiguration();
			repositoryConfiguration.add(0, new RepositoryConfiguration("local", new File("repository").toURI(), true));

			String[] classpaths = { "." };
			for (String classpath : classpaths) {
				loader.addClasspath(classpath);
			}

			System.setProperty("groovy.grape.report.downloads", "true");
			// System.setProperty("grape.root", ".");

			AetherGrapeEngine grapeEngine = AetherGrapeEngineFactory.create(loader, repositoryConfiguration,
					resolutionContext);

			// GrapeEngineInstaller.install(grapeEngine);

			// TODO: get version dynamically?
			HashMap<String, String> dependency = new HashMap<>();
			dependency.put("group", "org.springframework.cloud.launcher");
			dependency.put("module", "spring-cloud-launcher-deployer");
			dependency.put("version", getVersion());
			URI[] uris = grapeEngine.resolve(null, dependency);
			// System.out.println("resolved URI's " + Arrays.asList(uris));
			for (URI uri : uris) {
				loader.addURL(uri.toURL());
			}
			log.debug("resolved URI's " + Arrays.asList(loader.getURLs()));
			return loader;
		}

		private String getVersion() {
			Package pkg = LauncherCommand.class.getPackage();
			return (pkg != null ? pkg.getImplementationVersion() : DEFAULT_VERSION);
		}

	}

}
