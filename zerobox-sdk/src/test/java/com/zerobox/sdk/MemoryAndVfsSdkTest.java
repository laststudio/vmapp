package com.zerobox.sdk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.zerobox.sdk.memory.MemoryPatternScanner;
import com.zerobox.sdk.memory.MemoryRegion;
import com.zerobox.sdk.vfs.VfsRule;
import java.util.List;
import org.junit.Test;

public class MemoryAndVfsSdkTest {

    @Test
    public void testWildcardPatternScannerMatchesAccurateOffsets() {
        byte[] buffer = new byte[] {
            0x00, 0x11, 0x48, (byte) 0x8B, 0x05, (byte) 0x99, (byte) 0xE8, 0x12, 0x34,
            0x55, 0x48, (byte) 0x8B, 0x7F, 0x01, (byte) 0xE8, 0x00
        };

        MemoryPatternScanner.CompiledPattern pattern =
                MemoryPatternScanner.compile("48 8B ? ?? E8");
        assertEquals(5, pattern.length());

        long baseAddr = 0x70001000L;
        List<Long> hits = MemoryPatternScanner.scanBuffer(
                buffer,
                buffer.length,
                baseAddr,
                pattern,
                0);

        assertEquals(2, hits.size());
        assertEquals(Long.valueOf(baseAddr + 2), hits.get(0));
        assertEquals(Long.valueOf(baseAddr + 10), hits.get(1));
    }

    @Test
    public void testParseMapsLineArm64AndArm32() {
        String arm64Line =
                "7b2d400000-7b2d445000 r-xp 00000000 fd:00 12345 /apex/com.android.runtime/lib64/bionic/libc.so";
        MemoryRegion r64 = MemoryRegion.parseMapsLine(arm64Line);
        assertNotNull(r64);
        assertEquals(0x7b2d400000L, r64.getStartAddress());
        assertEquals(0x7b2d445000L, r64.getEndAddress());
        assertTrue(r64.isReadable());
        assertFalse(r64.isWritable());
        assertTrue(r64.isExecutable());
        assertTrue(r64.isPrivate());
        assertEquals("/apex/com.android.runtime/lib64/bionic/libc.so", r64.getPath());

        String arm32Line =
                "e9400000-e9450000 rw-p 00012000 fe:01 54321 /system/lib/libc.so";
        MemoryRegion r32 = MemoryRegion.parseMapsLine(arm32Line);
        assertNotNull(r32);
        assertEquals(0xe9400000L, r32.getStartAddress());
        assertEquals(0xe9450000L, r32.getEndAddress());
        assertTrue(r32.isReadable());
        assertTrue(r32.isWritable());
        assertFalse(r32.isExecutable());
        assertEquals("/system/lib/libc.so", r32.getPath());
    }

    @Test
    public void testVfsRulePathNormalization() {
        VfsRule rule = VfsRule.redirect("/data/user/0//com.demo/", "/host/data/com.demo///");
        assertEquals("/data/user/0/com.demo", rule.getVirtualPrefix());
        assertEquals("/host/data/com.demo", rule.getTargetPrefix());
    }
}
