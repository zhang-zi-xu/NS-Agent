using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;

namespace Nongxin.Development
{
    // Windows 10/11; suspended creation prevents an unowned child-process race.
    public sealed class WindowsJob : IDisposable
    {
        private IntPtr handle;
        public string Name { get; private set; }

        [StructLayout(LayoutKind.Sequential)]
        private struct BasicLimits
        {
            public long ProcessTime, JobTime;
            public uint Flags;
            public UIntPtr MinWorkingSet, MaxWorkingSet;
            public uint ActiveProcessLimit;
            public UIntPtr Affinity;
            public uint PriorityClass, SchedulingClass;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct IoCounters { public ulong A, B, C, D, E, F; }

        [StructLayout(LayoutKind.Sequential)]
        private struct ExtendedLimits
        {
            public BasicLimits Basic;
            public IoCounters Io;
            public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct SecurityAttributes
        {
            public int Length;
            public IntPtr Descriptor;
            [MarshalAs(UnmanagedType.Bool)] public bool Inherit;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        private struct StartupInfo
        {
            public int Size;
            public string Reserved, Desktop, Title;
            public uint X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
            public short ShowWindow, ReservedSize;
            public IntPtr Reserved2, StdInput, StdOutput, StdError;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct ProcessInfo { public IntPtr Process, Thread; public uint ProcessId, ThreadId; }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateJobObject(IntPtr attributes, string name);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool SetInformationJobObject(IntPtr job, int type, ref ExtendedLimits info, uint size);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern bool CreateProcess(string app, StringBuilder command, IntPtr processAttributes,
            IntPtr threadAttributes, bool inherit, uint flags, IntPtr environment, string directory,
            ref StartupInfo startup, out ProcessInfo process);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr CreateFile(string filename, uint access, uint share,
            ref SecurityAttributes attributes, uint disposition, uint flags, IntPtr template);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern uint ResumeThread(IntPtr thread);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool TerminateProcess(IntPtr process, uint code);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool TerminateJobObject(IntPtr job, uint code);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern IntPtr OpenJobObject(uint access, bool inherit, string name);
        [DllImport("kernel32.dll", SetLastError = true)] private static extern bool CloseHandle(IntPtr value);

        public WindowsJob(string name)
        {
            Name = name;
            handle = CreateJobObject(IntPtr.Zero, name);
            if (handle == IntPtr.Zero) throw new Win32Exception();
            var info = new ExtendedLimits();
            info.Basic.Flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if (!SetInformationJobObject(handle, 9, ref info, (uint)Marshal.SizeOf(info)))
            { Dispose(); throw new Win32Exception(); }
        }

        private static string Quote(string value)
        {
            var result = new StringBuilder("\"");
            int slashes = 0;
            foreach (char c in value)
            {
                if (c == '\\') { slashes++; continue; }
                if (c == '"') { result.Append('\\', slashes * 2 + 1).Append(c); slashes = 0; continue; }
                result.Append('\\', slashes).Append(c); slashes = 0;
            }
            return result.Append('\\', slashes * 2).Append('"').ToString();
        }

        public Process Start(string executable, string[] arguments, string directory,
            IDictionary<string, string> environment, string stdout, string stderr)
        {
            if (handle == IntPtr.Zero) throw new ObjectDisposedException("WindowsJob");
            var attributes = new SecurityAttributes { Length = Marshal.SizeOf(typeof(SecurityAttributes)), Inherit = true };
            IntPtr output = IntPtr.Zero, error = IntPtr.Zero, input = IntPtr.Zero, block = IntPtr.Zero;
            var child = new ProcessInfo();
            bool resumed = false;
            try
            {
                output = CreateFile(stdout, 0x40000000, 3, ref attributes, 2, 0x80, IntPtr.Zero);
                error = CreateFile(stderr, 0x40000000, 3, ref attributes, 2, 0x80, IntPtr.Zero);
                input = CreateFile("NUL", 0x80000000, 3, ref attributes, 3, 0x80, IntPtr.Zero);
                if (output == new IntPtr(-1) || error == new IntPtr(-1) || input == new IntPtr(-1)) throw new Win32Exception();
                string text = string.Join("\0", environment.OrderBy(p => p.Key, StringComparer.OrdinalIgnoreCase)
                    .Select(p => p.Key + "=" + p.Value)) + "\0\0";
                block = Marshal.StringToHGlobalUni(text);
                var startup = new StartupInfo { Size = Marshal.SizeOf(typeof(StartupInfo)), Flags = 0x101,
                    ShowWindow = 0, StdInput = input, StdOutput = output, StdError = error };
                var command = new StringBuilder(Quote(executable) + " " + string.Join(" ", arguments.Select(Quote)));
                if (!CreateProcess(executable, command, IntPtr.Zero, IntPtr.Zero, true,
                    0x08000404, block, directory, ref startup, out child)) throw new Win32Exception();
                if (!AssignProcessToJobObject(handle, child.Process)) throw new Win32Exception();
                var process = Process.GetProcessById((int)child.ProcessId);
                // Adopt a live handle before resuming, even for a child that exits immediately.
                // GetProcessById alone can lose ExitCode on .NET Framework after native handle close.
                IntPtr retainedHandle = process.Handle;
                if (ResumeThread(child.Thread) == uint.MaxValue) { process.Dispose(); throw new Win32Exception(); }
                resumed = true;
                return process;
            }
            finally
            {
                if (!resumed && child.Process != IntPtr.Zero) TerminateProcess(child.Process, 1);
                if (child.Thread != IntPtr.Zero) CloseHandle(child.Thread);
                if (child.Process != IntPtr.Zero) CloseHandle(child.Process);
                foreach (IntPtr stream in new[] { output, error, input })
                    if (stream != IntPtr.Zero && stream != new IntPtr(-1)) CloseHandle(stream);
                if (block != IntPtr.Zero) Marshal.FreeHGlobal(block);
            }
        }

        public static void Stop(string name)
        {
            IntPtr job = OpenJobObject(8, false, name); // JOB_OBJECT_TERMINATE
            if (job == IntPtr.Zero) throw new Win32Exception();
            try { if (!TerminateJobObject(job, 0)) throw new Win32Exception(); }
            finally { CloseHandle(job); }
        }

        public void Dispose()
        {
            if (handle != IntPtr.Zero) { CloseHandle(handle); handle = IntPtr.Zero; }
            GC.SuppressFinalize(this);
        }
        ~WindowsJob() { Dispose(); }
    }
}
