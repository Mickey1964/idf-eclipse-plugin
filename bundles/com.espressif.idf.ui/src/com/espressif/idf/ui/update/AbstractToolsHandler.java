/*******************************************************************************
 * Copyright 2018-2019 Espressif Systems (Shanghai) PTE LTD. All rights reserved.
 * Use is subject to license terms.
 *******************************************************************************/
package com.espressif.idf.ui.update;

import java.util.List;
import java.util.Map;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleConstants;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

import com.aptana.core.ShellExecutable;
import com.aptana.core.util.ExecutableUtil;
import com.aptana.core.util.ProcessRunner;
import com.espressif.idf.core.IDFCorePlugin;
import com.espressif.idf.core.IDFEnvironmentVariables;
import com.espressif.idf.core.logging.Logger;
import com.espressif.idf.core.util.IDFUtil;
import com.espressif.idf.core.util.PyWinRegistryReader;
import com.espressif.idf.core.util.StringUtil;

/**
 * @author Kondal Kolipaka <kondal.kolipaka@espressif.com>
 *
 */
public abstract class AbstractToolsHandler extends AbstractHandler
{
	/**
	 * Tools console
	 */
	protected MessageConsoleStream console;
	protected String idfPath;
	protected String pythonExecutablenPath;
	private String gitExecutablePath;

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException
	{
		String commmand_id = event.getCommand().getId();
		Logger.log("Command id:" + commmand_id); //$NON-NLS-1$

		// Get IDF_PATH
		idfPath = IDFUtil.getIDFPath();
		Logger.log("IDF_PATH :" + idfPath); //$NON-NLS-1$

		// Look for git path
		IPath gitPath = ExecutableUtil.find("git", true, null); //$NON-NLS-1$
		Logger.log("GIT path:" + gitPath); //$NON-NLS-1$
		if (gitPath != null)
		{
			this.gitExecutablePath = gitPath.toOSString();
		}

		// Get Python
		Map<String, String> pythonVersions = null;
		if (Platform.OS_WIN32.equals(Platform.getOS()))
		{
			PyWinRegistryReader pyWinRegistryReader = new PyWinRegistryReader();
			pythonVersions = pyWinRegistryReader.getPythonVersions();
			if (pythonVersions.isEmpty())
			{
				Logger.log("No Python installations found in the system."); //$NON-NLS-1$
			}
			if (pythonVersions.size() == 1)
			{
				Map.Entry<String, String> entry = pythonVersions.entrySet().iterator().next();
				pythonExecutablenPath = entry.getValue();
			}
		}
		else
		{
			pythonExecutablenPath = IDFUtil.getPythonExecutable();
		}

		// Let user choose
		DirectorySelectionDialog dir = new DirectorySelectionDialog(Display.getDefault().getActiveShell(),
				pythonExecutablenPath, pythonVersions, idfPath, gitExecutablePath);
		if (dir.open() == Window.OK)
		{
			idfPath = dir.getIDFDirectory();
			gitExecutablePath = dir.getGitExecutable();
			pythonExecutablenPath = dir.getPythonExecutable();
		}
		else
		{
			return null; // dialog is cancelled
		}

		if (StringUtil.isEmpty(pythonExecutablenPath) || StringUtil.isEmpty(gitExecutablePath)
				|| StringUtil.isEmpty(idfPath))
		{
			return null;
		}

		// Add IDF_PATH to the eclipse CDT build environment variables
		IDFEnvironmentVariables idfEnvMgr = new IDFEnvironmentVariables();
		idfEnvMgr.addEnvVariable(IDFEnvironmentVariables.IDF_PATH, idfPath);

		// Create Tools console
		MessageConsole msgConsole = findConsole(Messages.IDFToolsHandler_ToolsManagerConsole);
		msgConsole.clearConsole();
		console = msgConsole.newMessageStream();
		msgConsole.activate();

		// Open console view so that users can see the output
		openConsoleView();

		execute();

		return null;
	}

	/**
	 * Execute specific action
	 */
	protected abstract void execute();

	protected void runCommand(List<String> arguments)
	{
		ProcessRunner processRunner = new ProcessRunner();

		try
		{
			// insert python.sh/exe path and idf_tools.py
			arguments.add(0, pythonExecutablenPath);
			arguments.add(1, IDFUtil.getIDFToolsScriptFile().getAbsolutePath());

			console.println(Messages.AbstractToolsHandler_ExecutingMsg + " " + getCommandString(arguments));

			Map<String, String> environment = getEnvironment(Path.ROOT);
			Logger.log(environment.toString());

			addGitToEnvironment(environment, gitExecutablePath);

			IStatus status = processRunner.runInBackground(Path.ROOT, environment,
					arguments.toArray(new String[arguments.size()]));

			console.println(status.getMessage());
			console.println();

		}
		catch (Exception e1)
		{
			Logger.log(IDFCorePlugin.getPlugin(), e1);

		}
	}

	protected void addGitToEnvironment(Map<String, String> environment, String gitExecutablePath)
	{
		IPath gitPath = new Path(gitExecutablePath);
		if (gitPath.toFile().exists())
		{
			String gitDir = gitPath.removeLastSegments(1).toOSString();
			String path1 = environment.get("PATH"); //$NON-NLS-1$
			String path2 = environment.get("Path"); //$NON-NLS-1$
			if (!StringUtil.isEmpty(path1) && !path1.contains(gitDir)) // Git not found on the PATH environment
			{
				path1 = gitDir.concat(";").concat(path1);
				environment.put("PATH", path1); //$NON-NLS-1$
			}
			else if (!StringUtil.isEmpty(path2) && !path2.contains(gitDir)) // Git not found on the Path environment
			{
				path2 = gitDir.concat(";").concat(path2); //$NON-NLS-1$
				environment.put("Path", path2); //$NON-NLS-1$
			}
		}
	}

	protected String getCommandString(List<String> arguments)
	{
		StringBuilder builder = new StringBuilder();
		arguments.forEach(entry -> builder.append(entry + " ")); //$NON-NLS-1$

		return builder.toString().trim();
	}

	/**
	 * @param location
	 * @return
	 */
	protected Map<String, String> getEnvironment(IPath location)
	{
		return ShellExecutable.getEnvironment(location);
	}

	/**
	 * @return
	 */
	protected ILaunchManager getLaunchManager()
	{
		return DebugPlugin.getDefault().getLaunchManager();
	}

	/**
	 * Find a console for a given name. If not found, it will create a new one and return
	 * 
	 * @param name
	 * @return
	 */
	private MessageConsole findConsole(String name)
	{
		ConsolePlugin plugin = ConsolePlugin.getDefault();
		IConsoleManager conMan = plugin.getConsoleManager();
		IConsole[] existing = conMan.getConsoles();
		for (int i = 0; i < existing.length; i++)
		{
			if (name.equals(existing[i].getName()))
				return (MessageConsole) existing[i];
		}
		// no console found, so create a new one
		MessageConsole myConsole = new MessageConsole(name, null);
		conMan.addConsoles(new IConsole[] { myConsole });
		return myConsole;
	}

	protected void openConsoleView()
	{
		try
		{
			PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage()
					.showView(IConsoleConstants.ID_CONSOLE_VIEW);
		}
		catch (PartInitException e)
		{
			Logger.log(e);
		}
	}
}
