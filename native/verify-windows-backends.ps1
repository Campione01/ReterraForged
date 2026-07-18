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

        public Backend(string path) {
            library = NativeLibrary.Load(path);
            GetAbiVersion getAbi = Load<GetAbiVersion>("rtf_quick_noise_abi_version");
            if (getAbi() != AbiVersion) {
                throw new InvalidOperationException($"Unexpected ABI version in {path}");
            }
            FillCave = Load<FillTile>("rtf_quick_noise_fill_cave_tile_v1");
            Compile = Load<CompileProgram>("rtf_quick_noise_compile_program_v2");
            Outputs = Load<ProgramOutputs>("rtf_quick_noise_program_outputs_v2");
            Free = Load<FreeProgram>("rtf_quick_noise_free_program_v2");
            Fill2d = Load<FillProgram2d>("rtf_quick_noise_fill_program_2d_v2");
        }

        private T Load<T>(string name) where T : Delegate {
            return Marshal.GetDelegateForFunctionPointer<T>(NativeLibrary.GetExport(library, name));
        }

        public void Dispose() {
            NativeLibrary.Free(library);
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
                string path = Path.Combine(directory, $"reterraforged_quick_noise_{variant}.dll");
                using (Backend candidate = new Backend(path)) {
                    caveComparisons += VerifyCaves(scalar, candidate, variant);
                }
            }
        }
        using (Backend sse42 = new Backend(sse42Path)) {
            programComparisons += VerifyProgram(sse42, "sse42", program);
        }
        foreach (string variant in programVariants) {
            string path = Path.Combine(directory, $"reterraforged_quick_noise_{variant}.dll");
            using (Backend candidate = new Backend(path)) {
                programComparisons += VerifyProgram(candidate, variant, program);
            }
        }

        return $"Verified scalar/{string.Join("/", caveVariants)} QUICK_V1 parity for {caveComparisons} samples and per-backend QUICK_V2 determinism for {programComparisons} samples";
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

    private static int VerifyProgram(Backend candidate, string variant, byte[] program) {
        IntPtr programBuffer = Marshal.AllocHGlobal(program.Length);
        Marshal.Copy(program, 0, programBuffer, program.Length);
        ulong candidateHandle = 0;
        try {
            int candidateCompile = candidate.Compile(programBuffer, (UIntPtr)program.Length, out candidateHandle);
            if (candidateCompile != 0) {
                throw new InvalidOperationException($"{variant} QUICK_V2 compile failed with status {candidateCompile}");
            }
            int roots = checked((int)candidate.Outputs(candidateHandle).ToUInt64());
            if (roots == 0) {
                throw new InvalidOperationException($"{variant} QUICK_V2 program has no roots");
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
                    int expectedStatus = candidate.Fill2d(candidateHandle, seeds[testCase], regions[testCase, 0], regions[testCase, 1], (UIntPtr)width, (UIntPtr)height, expectedBuffer, (UIntPtr)samples);
                    int actualStatus = candidate.Fill2d(candidateHandle, seeds[testCase], regions[testCase, 0], regions[testCase, 1], (UIntPtr)width, (UIntPtr)height, actualBuffer, (UIntPtr)samples);
                    RequireSuccess(expectedStatus, actualStatus, variant, variant, "QUICK_V2 repeat", testCase);
                    Compare(expectedBuffer, actualBuffer, samples, variant, variant, "QUICK_V2 repeat", testCase);
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
                            throw new InvalidOperationException($"{variant} QUICK_V2 overlap mismatch at root {root}, x={x}, z={z}: left=0x{left[leftIndex]:X8}, right=0x{right[rightIndex]:X8}");
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
            throw new InvalidOperationException($"{reference}/{variant} {stage} failure in case {testCase}: reference={expected}, candidate={actual}");
        }
    }

    private static void Compare(IntPtr expectedBuffer, IntPtr actualBuffer, int samples, string reference, string variant, string stage, int testCase) {
        int[] expected = new int[samples];
        int[] actual = new int[samples];
        Marshal.Copy(expectedBuffer, expected, 0, samples);
        Marshal.Copy(actualBuffer, actual, 0, samples);
        for (int sample = 0; sample < samples; sample++) {
            if (expected[sample] != actual[sample]) {
                throw new InvalidOperationException($"{reference}/{variant} {stage} mismatch in case {testCase}, sample {sample}: reference=0x{expected[sample]:X8}, candidate=0x{actual[sample]:X8}");
            }
        }
    }

    private static byte[] CreateProgram() {
        using (MemoryStream stream = new MemoryStream())
        using (BinaryWriter writer = new BinaryWriter(stream)) {
            writer.Write(0x32564E51U);
            writer.Write(2U);
            writer.Write(16U);
            writer.Write(16U);
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
            for (uint root = 0; root < 16; root++) {
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
if ([Runtime.Intrinsics.X86.Avx2]::IsSupported -and [Runtime.Intrinsics.X86.Fma]::IsSupported) {
    $caveVariants += 'avx2'
    $programVariants += 'avx2'
}
if ([Runtime.Intrinsics.X86.Avx512F]::IsSupported -and [Runtime.Intrinsics.X86.Fma]::IsSupported) {
    $caveVariants += 'avx512'
    $programVariants += 'avx512'
}
if ($caveVariants.Count -eq 0 -and $programVariants.Count -eq 0) {
    Write-Output 'Skipped SIMD parity verification because no built SIMD backend is supported on this machine'
    exit 0
}

$result = [QuickNoiseBackendParity]::Verify([IO.Path]::GetFullPath($BinaryDirectory), $caveVariants, $programVariants)
Write-Output $result
