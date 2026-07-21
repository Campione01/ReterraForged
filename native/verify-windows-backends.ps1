param(
    [string] $BinaryDirectory = (Join-Path $PSScriptRoot 'binaries\windows-x86_64')
)

$ErrorActionPreference = 'Stop'

Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;

public static class QuickNoiseBackendParity {
    private const int CaveSamples = 32 * 32 * 32;
    private const uint AbiVersion = 2;

    [DllImport("kernel32")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsProcessorFeaturePresent(uint processorFeature);

    public static bool SupportsFeature(uint processorFeature) {
        return IsProcessorFeaturePresent(processorFeature);
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate uint GetAbiVersion();

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int FillTile(long seed, int tileX, int tileY, int tileZ, float chamberBias, float spaghettiWidth, float noodleWidth, IntPtr output);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int CompileProgram(IntPtr bytes, UIntPtr length, out ulong handle);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate UIntPtr ProgramOutputs(ulong handle);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int FreeProgram(ulong handle);

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int FillProgram2d(ulong handle, long seed, int originX, int originZ, UIntPtr width, UIntPtr height, IntPtr output, UIntPtr outputLength);

    private sealed class Backend : IDisposable {
        private readonly IntPtr library;
        public readonly FillTile FillCave;
        public readonly CompileProgram Compile;
        public readonly ProgramOutputs Outputs;
        public readonly FreeProgram Free;
        public readonly FillProgram2d Fill2d;

        [DllImport("kernel32", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern IntPtr LoadLibrary(string path);

        [DllImport("kernel32", CharSet = CharSet.Ansi, SetLastError = true)]
        private static extern IntPtr GetProcAddress(IntPtr library, string name);

        [DllImport("kernel32", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool FreeLibrary(IntPtr library);

        public Backend(string path) {
            library = LoadLibrary(path);
            if (library == IntPtr.Zero) {
                throw new InvalidOperationException("Could not load " + path + "; Win32 error " + Marshal.GetLastWin32Error());
            }
            GetAbiVersion getAbi = Load<GetAbiVersion>("rtf_quick_noise_abi_version");
            if (getAbi() != AbiVersion) {
                throw new InvalidOperationException("Unexpected ABI version in " + path);
            }
            FillCave = Load<FillTile>("rtf_quick_noise_fill_cave_tile_v1");
            Compile = Load<CompileProgram>("rtf_quick_noise_compile_program_v2");
            Outputs = Load<ProgramOutputs>("rtf_quick_noise_program_outputs_v2");
            Free = Load<FreeProgram>("rtf_quick_noise_free_program_v2");
            Fill2d = Load<FillProgram2d>("rtf_quick_noise_fill_program_2d_v2");
        }

        private T Load<T>(string name) where T : class {
            IntPtr address = GetProcAddress(library, name);
            if (address == IntPtr.Zero) {
                throw new InvalidOperationException("Could not resolve " + name + "; Win32 error " + Marshal.GetLastWin32Error());
            }
            return (T)(object)Marshal.GetDelegateForFunctionPointer(address, typeof(T));
        }

        public void Dispose() {
            FreeLibrary(library);
        }
    }

    public static string Verify(string directory, string[] caveVariants, string[] programVariants) {
        string scalarPath = Path.Combine(directory, "reterraforged_quick_noise_scalar.dll");
        string sse42Path = Path.Combine(directory, "reterraforged_quick_noise_sse42.dll");
        byte[] program = CreateProgram();
        int caveComparisons = 0;
        int programComparisons = 0;

        using (Backend scalar = new Backend(scalarPath)) {
            foreach (string variant in caveVariants) {
                string path = Path.Combine(directory, "reterraforged_quick_noise_" + variant + ".dll");
                using (Backend candidate = new Backend(path)) {
                    caveComparisons += VerifyCaves(scalar, candidate, variant);
                }
            }

            using (Backend sse42 = new Backend(sse42Path)) {
                programComparisons += VerifyProgram(scalar, sse42, "sse42", program);
            }
            foreach (string variant in programVariants) {
                string path = Path.Combine(directory, "reterraforged_quick_noise_" + variant + ".dll");
                using (Backend candidate = new Backend(path)) {
                    programComparisons += VerifyProgram(scalar, candidate, variant, program);
                }
            }
        }

        return string.Format("Verified scalar/{0} QUICK_V1 parity for {1} samples and scalar/SSE4.2/{2} QUICK_V2 parity for {3} samples", string.Join("/", caveVariants), caveComparisons, string.Join("/", programVariants), programComparisons);
    }

    private static int VerifyCaves(Backend scalar, Backend candidate, string variant) {
        long[] seeds = { 0x5EED1234L, -0x123456789ABCDEL, 91L, long.MaxValue };
        int[,] coordinates = { { 0, 0, 0 }, { -3, -1, 5 }, { 17, 0, -11 }, { -2048, 1, 2047 } };
        float[,] parameters = { { 0.32F, 0.085F, 0.055F }, { 0.44F, 0.063F, 0.031F }, { 0.8F, 0.0F, 0.055F }, { 0.32F, 0.085F, 0.0F } };
        IntPtr expectedBuffer = Marshal.AllocHGlobal(CaveSamples * sizeof(float));
        IntPtr actualBuffer = Marshal.AllocHGlobal(CaveSamples * sizeof(float));
        try {
            for (int testCase = 0; testCase < seeds.Length; testCase++) {
                int expectedStatus = scalar.FillCave(seeds[testCase], coordinates[testCase, 0], coordinates[testCase, 1], coordinates[testCase, 2], parameters[testCase, 0], parameters[testCase, 1], parameters[testCase, 2], expectedBuffer);
                int actualStatus = candidate.FillCave(seeds[testCase], coordinates[testCase, 0], coordinates[testCase, 1], coordinates[testCase, 2], parameters[testCase, 0], parameters[testCase, 1], parameters[testCase, 2], actualBuffer);
                RequireSuccess(expectedStatus, actualStatus, "scalar", variant, "QUICK_V1", testCase);
                Compare(expectedBuffer, actualBuffer, CaveSamples, "scalar", variant, "QUICK_V1", testCase);
            }
            return seeds.Length * CaveSamples;
        } finally {
            Marshal.FreeHGlobal(actualBuffer);
            Marshal.FreeHGlobal(expectedBuffer);
        }
    }

    private static int VerifyProgram(Backend reference, Backend candidate, string variant, byte[] program) {
        IntPtr programBuffer = Marshal.AllocHGlobal(program.Length);
        Marshal.Copy(program, 0, programBuffer, program.Length);
        ulong referenceHandle = 0;
        ulong candidateHandle = 0;
        try {
            int referenceCompile = reference.Compile(programBuffer, (UIntPtr)program.Length, out referenceHandle);
            int candidateCompile = candidate.Compile(programBuffer, (UIntPtr)program.Length, out candidateHandle);
            if (referenceCompile != 0 || candidateCompile != 0) {
                throw new InvalidOperationException(string.Format("scalar/{0} QUICK_V2 compile failed: scalar={1}, candidate={2}", variant, referenceCompile, candidateCompile));
            }
            int referenceRoots = checked((int)reference.Outputs(referenceHandle).ToUInt64());
            int roots = checked((int)candidate.Outputs(candidateHandle).ToUInt64());
            if (roots == 0 || roots != referenceRoots) {
                throw new InvalidOperationException(string.Format("scalar/{0} QUICK_V2 root mismatch: scalar={1}, candidate={2}", variant, referenceRoots, roots));
            }

            long[] seeds = { 991L, -72727272727L, long.MaxValue };
            int[,] regions = { { -64, 96, 32, 32 }, { 17, -49, 31, 29 }, { -2048, 2047, 48, 24 } };
            int total = 0;
            for (int testCase = 0; testCase < seeds.Length; testCase++) {
                int width = regions[testCase, 2];
                int height = regions[testCase, 3];
                int samples = checked(width * height * roots);
                IntPtr expectedBuffer = Marshal.AllocHGlobal(samples * sizeof(float));
                IntPtr actualBuffer = Marshal.AllocHGlobal(samples * sizeof(float));
                try {
                    int expectedStatus = reference.Fill2d(referenceHandle, seeds[testCase], regions[testCase, 0], regions[testCase, 1], (UIntPtr)width, (UIntPtr)height, expectedBuffer, (UIntPtr)samples);
                    int actualStatus = candidate.Fill2d(candidateHandle, seeds[testCase], regions[testCase, 0], regions[testCase, 1], (UIntPtr)width, (UIntPtr)height, actualBuffer, (UIntPtr)samples);
                    RequireSuccess(expectedStatus, actualStatus, "scalar", variant, "QUICK_V2", testCase);
                    Compare(expectedBuffer, actualBuffer, samples, "scalar", variant, "QUICK_V2", testCase);
                    total += samples;
                } finally {
                    Marshal.FreeHGlobal(actualBuffer);
                    Marshal.FreeHGlobal(expectedBuffer);
                }
            }
            total += VerifyOverlap(candidate, candidateHandle, variant, roots);
            return total;
        } finally {
            if (candidateHandle != 0) {
                candidate.Free(candidateHandle);
            }
            if (referenceHandle != 0) {
                reference.Free(referenceHandle);
            }
            Marshal.FreeHGlobal(programBuffer);
        }
    }

    private static int VerifyOverlap(Backend backend, ulong handle, string variant, int roots) {
        const int width = 32;
        const int height = 32;
        const int overlap = 16;
        int samples = checked(width * height * roots);
        IntPtr leftBuffer = Marshal.AllocHGlobal(samples * sizeof(float));
        IntPtr rightBuffer = Marshal.AllocHGlobal(samples * sizeof(float));
        try {
            int leftStatus = backend.Fill2d(handle, 72L, 0, 0, (UIntPtr)width, (UIntPtr)height, leftBuffer, (UIntPtr)samples);
            int rightStatus = backend.Fill2d(handle, 72L, overlap, 0, (UIntPtr)width, (UIntPtr)height, rightBuffer, (UIntPtr)samples);
            RequireSuccess(leftStatus, rightStatus, variant, variant, "QUICK_V2 overlap", 0);
            int[] left = new int[samples];
            int[] right = new int[samples];
            Marshal.Copy(leftBuffer, left, 0, samples);
            Marshal.Copy(rightBuffer, right, 0, samples);
            int fieldSamples = width * height;
            int compared = 0;
            for (int root = 0; root < roots; root++) {
                int fieldOffset = root * fieldSamples;
                for (int z = 0; z < height; z++) {
                    for (int x = overlap; x < width; x++) {
                        int leftIndex = fieldOffset + z * width + x;
                        int rightIndex = fieldOffset + z * width + x - overlap;
                        if (left[leftIndex] != right[rightIndex]) {
                            throw new InvalidOperationException(string.Format("{0} QUICK_V2 overlap mismatch at root {1}, x={2}, z={3}: left=0x{4:X8}, right=0x{5:X8}", variant, root, x, z, left[leftIndex], right[rightIndex]));
                        }
                        compared++;
                    }
                }
            }
            return compared;
        } finally {
            Marshal.FreeHGlobal(rightBuffer);
            Marshal.FreeHGlobal(leftBuffer);
        }
    }

    private static void RequireSuccess(int expected, int actual, string reference, string variant, string stage, int testCase) {
        if (expected != 0 || actual != 0) {
            throw new InvalidOperationException(string.Format("{0}/{1} {2} failure in case {3}: reference={4}, candidate={5}", reference, variant, stage, testCase, expected, actual));
        }
    }

    private static void Compare(IntPtr expectedBuffer, IntPtr actualBuffer, int samples, string reference, string variant, string stage, int testCase) {
        int[] expected = new int[samples];
        int[] actual = new int[samples];
        Marshal.Copy(expectedBuffer, expected, 0, samples);
        Marshal.Copy(actualBuffer, actual, 0, samples);
        for (int sample = 0; sample < samples; sample++) {
            if (expected[sample] != actual[sample]) {
                throw new InvalidOperationException(string.Format("{0}/{1} {2} mismatch in case {3}, sample {4}: reference=0x{5:X8}, candidate=0x{6:X8}", reference, variant, stage, testCase, sample, expected[sample], actual[sample]));
            }
        }
    }

    private static byte[] CreateProgram() {
        using (MemoryStream stream = new MemoryStream())
        using (BinaryWriter writer = new BinaryWriter(stream)) {
            writer.Write(0x32564E51U);
            writer.Write(3U);
            writer.Write(31U);
            writer.Write(13U);
            writer.Write(48U);
            WriteNode(writer, 1, 4, -1, -1, 0x514E5632L, 1F / 64F, 2F, 0.5F, 1F, 1F, 1F);
            WriteNode(writer, 2, 4, -1, -1, 0x56414C55L, 1F / 96F, 2.1F, 0.45F, 0.8F, 1F, 1F);
            WriteNode(writer, 3, 3, -1, -1, 0x53494D50L, 1F / 48F, 2F, 0.5F, 1F, 1F, 1F);
            WriteNode(writer, 4, 2, -1, -1, 0x43454C4CL, 1F / 80F, 2F, 0.5F, 1F, 1F, 1F);
            WriteNode(writer, 5, 4, -1, -1, 0x42494C4CL, 1F / 72F, 2F, 0.55F, 1F, 1F, 1F);
            WriteNode(writer, 6, 4, -1, -1, 0x52494447L, 1F / 88F, 2F, 0.45F, 1F, 1F, 1F);
            WriteNode(writer, 16, 0, 1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 17, 2, 3, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 18, 4, 5, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 19, 6, 7, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 20, 8, -1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 21, 9, -1, -1, 0L, -0.65F, 0.65F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 22, 10, -1, -1, 0L, 0F, 1F, -1F, 1F, 0F, 0F);
            WriteNode(writer, 23, 11, -1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 24, 12, -1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 25, 13, -1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 32, -1, -1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 33, -1, -1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 8, 4, 16, 17, 0x5045524CL, 1F / 67F, 2.05F, 0.48F, -1F, 1F, 1F);
            WriteNode(writer, 9, 3, 16, 17, 0x50455232L, 1F / 71F, 2F, 0.52F, -1F, 1F, 2F);
            WriteNode(writer, 10, 4, 16, 17, 0x53494D31L, 1F / 73F, 2.1F, 0.47F, -1F, 1F, 0F);
            WriteNode(writer, 11, 3, 16, 17, 0x53494D32L, 1F / 79F, 1.95F, 0.53F, -1F, 1F, 0F);
            WriteNode(writer, 12, 4, 16, 17, 0x52494450L, 1F / 83F, 2F, 0.5F, 0F, 1F, 1F);
            WriteNode(writer, 13, 4, 16, 17, 0x52494453L, 1F / 89F, 2F, 0.5F, 0F, 1F, 0F);
            WriteNode(writer, 14, 4, 16, 17, 0x42494C4CL, 1F / 97F, 2F, 0.5F, 0F, 1F, 1F);
            WriteNode(writer, 15, 3, 16, 17, 0x43554245L, 1F / 101F, 2F, 0.5F, -1F, 1F, 0F);
            WriteNode(writer, 40, 1, 16, 17, 0x57484954L, 1F / 17F, 0F, 0F, 0F, 1F, 0F);
            WriteNode(writer, 41, 1, 16, 17, 0x574F524CL, 1F / 107F, 0.85F, 0F, 1F, -1F, 1F);
            WriteNode(writer, 42, 1, 16, 17, 0x45444745L, 1F / 109F, 0.9F, 3F, 2F, 0F, 1F);
            WriteNode(writer, 43, 16, -1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            WriteNode(writer, 44, 17, -1, -1, 0L, 0F, 0F, 0F, 0F, 0F, 0F);
            for (uint root = 18; root < 31; root++) {
                writer.Write(root);
            }
            return stream.ToArray();
        }
    }

    private static void WriteNode(BinaryWriter writer, uint opcode, int inputA, int inputB, int inputC, long seedOffset, params float[] values) {
        if (values.Length != 6) {
            throw new ArgumentException("A QUICK_V2 node requires six parameters");
        }
        writer.Write(opcode);
        writer.Write(inputA);
        writer.Write(inputB);
        writer.Write(inputC);
        writer.Write(seedOffset);
        foreach (float value in values) {
            writer.Write(value);
        }
    }
}
'@

$caveVariants = @()
$programVariants = @()
if ([QuickNoiseBackendParity]::SupportsFeature(40)) {
    $caveVariants += 'avx2'
    $programVariants += 'avx2'
}
if ([QuickNoiseBackendParity]::SupportsFeature(41)) {
    $caveVariants += 'avx512'
    $programVariants += 'avx512'
}
if ($caveVariants.Count -eq 0 -and $programVariants.Count -eq 0) {
    Write-Output 'Skipped SIMD parity verification because no built SIMD backend is supported on this machine'
    exit 0
}

$result = [QuickNoiseBackendParity]::Verify([IO.Path]::GetFullPath($BinaryDirectory), $caveVariants, $programVariants)
Write-Output $result
