param(
    [string] $BinaryDirectory = (Join-Path $PSScriptRoot 'binaries\windows-x86_64')
)

$ErrorActionPreference = 'Stop'

if (-not [Runtime.Intrinsics.X86.Avx2]::IsSupported -or -not [Runtime.Intrinsics.X86.Fma]::IsSupported) {
    Write-Output 'Skipped scalar/AVX2 parity verification because AVX2+FMA is unavailable on this build machine'
    exit 0
}

Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;

public static class QuickNoiseBackendParity {
    private const int Samples = 32 * 32 * 32;

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    private delegate int FillTile(long seed, int tileX, int tileY, int tileZ, float chamberBias, float spaghettiWidth, float noodleWidth, IntPtr output);

    public static int Verify(string directory) {
        IntPtr scalarLibrary = NativeLibrary.Load(Path.Combine(directory, "reterraforged_quick_noise_scalar.dll"));
        IntPtr avx2Library = NativeLibrary.Load(Path.Combine(directory, "reterraforged_quick_noise_avx2.dll"));
        IntPtr scalarBuffer = Marshal.AllocHGlobal(Samples * sizeof(float));
        IntPtr avx2Buffer = Marshal.AllocHGlobal(Samples * sizeof(float));
        try {
            FillTile scalar = Marshal.GetDelegateForFunctionPointer<FillTile>(NativeLibrary.GetExport(scalarLibrary, "rtf_quick_noise_fill_cave_tile_v1"));
            FillTile avx2 = Marshal.GetDelegateForFunctionPointer<FillTile>(NativeLibrary.GetExport(avx2Library, "rtf_quick_noise_fill_cave_tile_v1"));
            long[] seeds = { 0x5EED1234L, -0x123456789ABCDEL, 91L, long.MaxValue };
            int[,] coordinates = { { 0, 0, 0 }, { -3, -1, 5 }, { 17, 0, -11 }, { -2048, 1, 2047 } };
            float[,] parameters = { { 0.32F, 0.085F, 0.055F }, { 0.44F, 0.063F, 0.031F }, { 0.8F, 0.0F, 0.055F }, { 0.32F, 0.085F, 0.0F } };
            int[] expected = new int[Samples];
            int[] actual = new int[Samples];
            for (int testCase = 0; testCase < seeds.Length; testCase++) {
                int scalarResult = scalar(seeds[testCase], coordinates[testCase, 0], coordinates[testCase, 1], coordinates[testCase, 2], parameters[testCase, 0], parameters[testCase, 1], parameters[testCase, 2], scalarBuffer);
                int avx2Result = avx2(seeds[testCase], coordinates[testCase, 0], coordinates[testCase, 1], coordinates[testCase, 2], parameters[testCase, 0], parameters[testCase, 1], parameters[testCase, 2], avx2Buffer);
                if (scalarResult != 0 || avx2Result != 0) {
                    throw new InvalidOperationException($"Native backend failure in case {testCase}: scalar={scalarResult}, avx2={avx2Result}");
                }
                Marshal.Copy(scalarBuffer, expected, 0, Samples);
                Marshal.Copy(avx2Buffer, actual, 0, Samples);
                for (int sample = 0; sample < Samples; sample++) {
                    if (expected[sample] != actual[sample]) {
                        throw new InvalidOperationException($"Scalar/AVX2 mismatch in case {testCase}, sample {sample}: scalar=0x{expected[sample]:X8}, avx2=0x{actual[sample]:X8}");
                    }
                }
            }
            return seeds.Length * Samples;
        } finally {
            Marshal.FreeHGlobal(scalarBuffer);
            Marshal.FreeHGlobal(avx2Buffer);
            NativeLibrary.Free(avx2Library);
            NativeLibrary.Free(scalarLibrary);
        }
    }
}
'@

$samples = [QuickNoiseBackendParity]::Verify([IO.Path]::GetFullPath($BinaryDirectory))
Write-Output "Verified scalar/AVX2 raw-bit parity for $samples QUICK_V1 samples"
