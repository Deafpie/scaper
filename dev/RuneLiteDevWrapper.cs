using System;
using System.Diagnostics;
using System.IO;

/// <summary>
/// Wrapper EXE that sits in place of RuneLite.exe.
/// When the Jagex Launcher calls this, it forwards all arguments
/// to the Scaper dev client (gradle launchRuneLite), so the dev
/// client receives the Jagex auth credentials and can log in.
/// </summary>
class RuneLiteDevWrapper
{
    static void Main(string[] args)
    {
        string pluginDir = @"C:\Users\Jared\Desktop\scaper-plugin";
        string gradlew = Path.Combine(pluginDir, "gradlew.bat");

        // Join all arguments (Jagex Launcher passes --jx_session_id, etc.)
        string joinedArgs = string.Join(" ", args);

        // Build the command: gradlew.bat launchRuneLite -Prl.args="<all jagex args>"
        string gradleArgs;
        if (args.Length > 0)
        {
            gradleArgs = string.Format("launchRuneLite \"-Prl.args={0}\"", joinedArgs);
        }
        else
        {
            gradleArgs = "launchRuneLite";
        }

        ProcessStartInfo psi = new ProcessStartInfo
        {
            FileName = gradlew,
            Arguments = gradleArgs,
            WorkingDirectory = pluginDir,
            UseShellExecute = false,
        };

        try
        {
            Process proc = Process.Start(psi);
            proc.WaitForExit();
        }
        catch (Exception ex)
        {
            // Log error to a file for debugging
            string logPath = Path.Combine(pluginDir, "wrapper_error.log");
            File.WriteAllText(logPath, DateTime.Now + " — " + ex.ToString());
        }
    }
}
