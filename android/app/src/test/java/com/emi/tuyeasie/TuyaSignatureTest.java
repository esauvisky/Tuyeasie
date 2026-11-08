package com.emi.tuyeasie;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class TuyaSignatureTest {
    private static final String EXPECTED_SHA256 =
            "93219fc273e2200f4adee5f7191dc656ba2a2d7b2ff5d24cd55c4b6155001e40";

    @Test
    public void embeddedCertificateHasExpectedSha256() throws IOException, NoSuchAlgorithmException {
        byte[] der = readDer();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(String.format("%02x", value & 0xff));
        }
        assertEquals(EXPECTED_SHA256, hex.toString());
    }

    private static byte[] readDer() throws IOException {
        File resource = new File("src/main/res/raw/tuya_signature.der");
        try (InputStream input = new FileInputStream(resource)) {
            return input.readAllBytes();
        }
    }
}
