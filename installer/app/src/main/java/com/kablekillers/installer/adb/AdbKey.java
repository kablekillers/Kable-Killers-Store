package com.kablekillers.installer.adb;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The phone's ADB identity. The TV remembers this key after "Always allow from this computer",
 * so it is generated once and kept in the app's private storage.
 */
public final class AdbKey {
    private static final int KEY_BITS = 2048;
    private static final int KEY_WORDS = KEY_BITS / 32;

    /** ASN.1 DigestInfo prefix for SHA-1; adbd treats the 20-byte token as a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;

    private AdbKey(RSAPrivateKey priv, RSAPublicKey pub) {
        this.privateKey = priv;
        this.publicKey = pub;
    }

    public static synchronized AdbKey loadOrCreate(File dir) throws IOException {
        File privFile = new File(dir, "adbkey");
        File pubFile = new File(dir, "adbkey.pub.der");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (privFile.exists() && pubFile.exists()) {
                RSAPrivateKey priv = (RSAPrivateKey) kf.generatePrivate(
                        new PKCS8EncodedKeySpec(Files.readAllBytes(privFile.toPath())));
                RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                        new X509EncodedKeySpec(Files.readAllBytes(pubFile.toPath())));
                return new AdbKey(priv, pub);
            }
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(KEY_BITS);
            KeyPair kp = gen.generateKeyPair();
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Can't create key folder");
            Files.write(privFile.toPath(), kp.getPrivate().getEncoded());
            Files.write(pubFile.toPath(), kp.getPublic().getEncoded());
            return new AdbKey((RSAPrivateKey) kp.getPrivate(), (RSAPublicKey) kp.getPublic());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Key error: " + e.getMessage(), e);
        }
    }

    /** Signs the 20-byte AUTH token: PKCS#1 v1.5 type-1 padding around SHA1 DigestInfo, then raw RSA. */
    public byte[] sign(byte[] token) {
        int k = KEY_BITS / 8;
        byte[] block = new byte[k];
        int tLen = SHA1_PREFIX.length + token.length;
        block[0] = 0x00;
        block[1] = 0x01;
        for (int i = 2; i < k - tLen - 1; i++) block[i] = (byte) 0xff;
        block[k - tLen - 1] = 0x00;
        System.arraycopy(SHA1_PREFIX, 0, block, k - tLen, SHA1_PREFIX.length);
        System.arraycopy(token, 0, block, k - token.length, token.length);
        BigInteger m = new BigInteger(1, block);
        BigInteger s = m.modPow(privateKey.getPrivateExponent(), privateKey.getModulus());
        return toFixed(s, k);
    }

    /** Public key in Android's adb format: base64(struct RSAPublicKey) + " name" + NUL. */
    public byte[] androidPublicKey(String name) {
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS).pow(2).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + KEY_BITS / 8 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(KEY_WORDS);
        bb.putInt(n0inv.intValue());
        putWords(bb, n);
        putWords(bb, rr);
        bb.putInt(publicKey.getPublicExponent().intValue());

        String b64 = Base64.getEncoder().encodeToString(bb.array());
        byte[] text = (b64 + " " + name).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[text.length + 1];
        System.arraycopy(text, 0, out, 0, text.length);
        return out;
    }

    private static void putWords(ByteBuffer bb, BigInteger v) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < KEY_WORDS; i++) {
            bb.putInt(v.shiftRight(32 * i).and(mask).intValue());
        }
    }

    private static byte[] toFixed(BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        if (raw.length >= len) {
            System.arraycopy(raw, raw.length - len, out, 0, len);
        } else {
            System.arraycopy(raw, 0, out, len - raw.length, raw.length);
        }
        return out;
    }
}
