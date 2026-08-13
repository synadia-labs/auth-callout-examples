package io.synadia.authcallout.server;

import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Curve25519 (xkey) decryption helper for NATS auth callout payloads.
 *
 * nkeys-java 2.1.1 does not support curve25519 seeds in NKey.fromSeed(), so we
 * decode the NKey-encoded seed manually (standard RFC 4648 base32) and perform
 * NaCl box decryption (X25519 + XSalsa20-Poly1305) using Bouncy Castle.
 *
 * NaCl box key derivation (matching Go's box.Precompute):
 *   rawDH   = X25519(myPriv, theirPub)
 *   boxKey  = HSalsa20(rawDH, zeros[16], sigma)   ← extra step many implementations miss
 *   stream  = XSalsa20(boxKey, nonce)              ← first 32 bytes = Poly1305 key
 *
 * NATS xkey encrypted payload format: nonce(24) | MAC(16) | ciphertext.
 * The sender's ephemeral public key arrives in the "Nats-Server-Xkey" header.
 */
class CurveKeyHelper {

    private static final Logger log = LoggerFactory.getLogger(CurveKeyHelper.class);
    private static final String BASE32_ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** NKey prefix byte for curve25519 public keys: 23 ('X') << 3 = 184 = 0xB8. */
    private static final int PREFIX_BYTE_CURVE = 23 << 3;

    private final X25519PrivateKeyParameters privateKeyParams;
    private final String publicKeyNKey;

    CurveKeyHelper(String seed) {
        // NKey seed (base32-decoded): 2 prefix bytes | 32 raw key bytes | 2 CRC bytes
        byte[] decoded = base32Decode(seed);
        byte[] rawPrivKey = Arrays.copyOfRange(decoded, 2, 34);
        this.privateKeyParams = new X25519PrivateKeyParameters(rawPrivKey);
        this.publicKeyNKey = encodePublicKeyNKey(privateKeyParams.generatePublicKey().getEncoded());
    }

    /** NKey-encoded public key (X...) — compare this to the xkey in your NATS server config. */
    String getPublicKeyNKey() {
        return publicKeyNKey;
    }

    /**
     * Decrypts a NATS xkey-encrypted auth callout payload using NaCl box.
     *
     * @param encrypted     raw bytes: nonce(24) | MAC(16) | ciphertext
     * @param senderNKeyStr NKey-encoded ephemeral public key from the Nats-Server-Xkey header
     * @return decrypted plaintext (the JWT bytes), or {@code null} on authentication failure
     */
    byte[] open(byte[] encrypted, String senderNKeyStr) {
        // NKey public key (base32-decoded): 1 prefix byte | 32 raw key bytes | 2 CRC bytes
        byte[] senderDecoded = base32Decode(senderNKeyStr);
        X25519PublicKeyParameters senderPubParams =
                new X25519PublicKeyParameters(Arrays.copyOfRange(senderDecoded, 1, 33));

        // NATS nkeys prepends "xkv1" (4 bytes) version prefix — skip it
        int offset = (encrypted.length > 4
                && encrypted[0] == 'x' && encrypted[1] == 'k'
                && encrypted[2] == 'v' && encrypted[3] == '1') ? 4 : 0;

        byte[] nonce       = Arrays.copyOfRange(encrypted, offset,      offset + 24);
        byte[] receivedMac = Arrays.copyOfRange(encrypted, offset + 24, offset + 40);
        byte[] ciphertext  = Arrays.copyOfRange(encrypted, offset + 40, encrypted.length);

        // ── 1. X25519 Diffie-Hellman ──────────────────────────────────────────
        byte[] rawDh = new byte[32];
        privateKeyParams.generateSecret(senderPubParams, rawDh, 0);

        // ── 2. box.Precompute: HSalsa20(rawDH, zeros) ────────────────────────
        byte[] boxKey = hSalsa20(rawDh, new byte[16]);

        // ── 3. XSalsa20 = Salsa20(HSalsa20(boxKey, nonce[0..15]), nonce[16..23]) ──
        byte[] subKey     = hSalsa20(boxKey, Arrays.copyOfRange(nonce, 0, 16));
        byte[] innerNonce = Arrays.copyOfRange(nonce, 16, 24);

        // ── 4. Poly1305 key = first 32 bytes of Salsa20 block at counter=0 ───
        byte[] polyKey = Arrays.copyOfRange(salsa20Block(subKey, innerNonce, 0), 0, 32);

        // ── 5. Verify Poly1305 MAC over ciphertext ────────────────────────────
        Poly1305 mac = new Poly1305();
        mac.init(new KeyParameter(polyKey));
        mac.update(ciphertext, 0, ciphertext.length);
        byte[] computedMac = new byte[16];
        mac.doFinal(computedMac, 0);
        if (!Arrays.equals(receivedMac, computedMac)) {
            return null; // authentication failure — wrong key or corrupted payload
        }

        // ── 6. Decrypt — NaCl secretbox keystream layout:
        //   block0[0..31]  = Poly1305 key (already used above)
        //   block0[32..63] = keystream for plaintext[0..31]
        //   block1[0..63]  = keystream for plaintext[32..95]
        //   block2[0..63]  = keystream for plaintext[96..159], …
        byte[] plaintext = new byte[ciphertext.length];
        byte[] block0 = salsa20Block(subKey, innerNonce, 0);
        int pos = 0;
        int firstChunk = Math.min(32, ciphertext.length);
        for (int i = 0; i < firstChunk; i++) {
            plaintext[i] = (byte) (ciphertext[i] ^ block0[32 + i]);
        }
        pos = firstChunk;
        for (int blockNum = 1; pos < ciphertext.length; blockNum++) {
            byte[] ks = salsa20Block(subKey, innerNonce, blockNum);
            int n = Math.min(64, ciphertext.length - pos);
            for (int i = 0; i < n; i++) plaintext[pos + i] = (byte) (ciphertext[pos + i] ^ ks[i]);
            pos += n;
        }
        return plaintext;
    }

    // ── HSalsa20 ──────────────────────────────────────────────────────────────

    /**
     * Implements HSalsa20 (the NaCl core function used in box.Precompute).
     * Applies 20 rounds of the Salsa20 core to (key, in) and outputs 8 specific
     * state words (indices 0,5,10,15,6,7,8,9) — without the final state addition.
     *
     * @param key  32-byte input key (raw X25519 shared secret)
     * @param in16 16-byte input (zeros for NaCl box key derivation)
     * @return 32-byte derived key
     */
    private static byte[] hSalsa20(byte[] key, byte[] in16) {
        // "expand 32-byte k" constant (sigma)
        int[] x = new int[16];
        x[0]  = 0x61707865;
        x[1]  = le32(key,  0);
        x[2]  = le32(key,  4);
        x[3]  = le32(key,  8);
        x[4]  = le32(key, 12);
        x[5]  = 0x3320646e;
        x[6]  = le32(in16, 0);
        x[7]  = le32(in16, 4);
        x[8]  = le32(in16, 8);
        x[9]  = le32(in16, 12);
        x[10] = 0x79622d32;
        x[11] = le32(key, 16);
        x[12] = le32(key, 20);
        x[13] = le32(key, 24);
        x[14] = le32(key, 28);
        x[15] = 0x6b206574;

        // 20 rounds (10 double rounds: column then row)
        for (int i = 0; i < 10; i++) {
            // Column round
            qr(x,  0,  4,  8, 12);
            qr(x,  5,  9, 13,  1);
            qr(x, 10, 14,  2,  6);
            qr(x, 15,  3,  7, 11);
            // Row round
            qr(x,  0,  1,  2,  3);
            qr(x,  5,  6,  7,  4);
            qr(x, 10, 11,  8,  9);
            qr(x, 15, 12, 13, 14);
        }

        // HSalsa20 output: words 0, 5, 10, 15, 6, 7, 8, 9 (no final state addition)
        byte[] out = new byte[32];
        le32Bytes(x[0],  out,  0);
        le32Bytes(x[5],  out,  4);
        le32Bytes(x[10], out,  8);
        le32Bytes(x[15], out, 12);
        le32Bytes(x[6],  out, 16);
        le32Bytes(x[7],  out, 20);
        le32Bytes(x[8],  out, 24);
        le32Bytes(x[9],  out, 28);
        return out;
    }

    /**
     * Produces one 64-byte Salsa20 keystream block.
     * State layout: sigma | key[0..15] | nonce[0..7] | counter(64-bit LE) | key[16..31] | sigma
     *
     * @param key     32-byte subkey
     * @param nonce8  8-byte inner nonce (nonce[16..23])
     * @param counter 32-bit block counter (0 = Poly1305 key block, 1+ = ciphertext)
     */
    private static byte[] salsa20Block(byte[] key, byte[] nonce8, int counter) {
        int[] s = new int[16];
        s[0]  = 0x61707865;
        s[1]  = le32(key, 0);   s[2]  = le32(key, 4);
        s[3]  = le32(key, 8);   s[4]  = le32(key, 12);
        s[5]  = 0x3320646e;
        s[6]  = le32(nonce8, 0); s[7]  = le32(nonce8, 4);
        s[8]  = counter;         s[9]  = 0;
        s[10] = 0x79622d32;
        s[11] = le32(key, 16);  s[12] = le32(key, 20);
        s[13] = le32(key, 24);  s[14] = le32(key, 28);
        s[15] = 0x6b206574;

        int[] x = s.clone();
        for (int i = 0; i < 10; i++) {
            qr(x,  0,  4,  8, 12); qr(x,  5,  9, 13,  1);
            qr(x, 10, 14,  2,  6); qr(x, 15,  3,  7, 11);
            qr(x,  0,  1,  2,  3); qr(x,  5,  6,  7,  4);
            qr(x, 10, 11,  8,  9); qr(x, 15, 12, 13, 14);
        }
        byte[] out = new byte[64];
        for (int i = 0; i < 16; i++) le32Bytes(x[i] + s[i], out, i * 4);
        return out;
    }

    /** Salsa20 quarter-round: QR(x[a], x[b], x[c], x[d]). */
    private static void qr(int[] x, int a, int b, int c, int d) {
        x[b] ^= rotl(x[a] + x[d],  7);
        x[c] ^= rotl(x[b] + x[a],  9);
        x[d] ^= rotl(x[c] + x[b], 13);
        x[a] ^= rotl(x[d] + x[c], 18);
    }

    private static int rotl(int v, int n) { return (v << n) | (v >>> (32 - n)); }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off+1] & 0xFF) << 8)
                | ((b[off+2] & 0xFF) << 16) | ((b[off+3] & 0xFF) << 24);
    }

    private static void le32Bytes(int v, byte[] b, int off) {
        b[off]   = (byte) v;
        b[off+1] = (byte)(v >>  8);
        b[off+2] = (byte)(v >> 16);
        b[off+3] = (byte)(v >> 24);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xFF));
        return sb.toString();
    }

    // ── NKey encoding helpers ─────────────────────────────────────────────────

    private static String encodePublicKeyNKey(byte[] rawPubKey) {
        // NKey public key: prefix(1) | key(32) | CRC16(2) = 35 bytes → base32 → 56 chars
        byte[] full = new byte[35];
        full[0] = (byte) PREFIX_BYTE_CURVE;
        System.arraycopy(rawPubKey, 0, full, 1, 32);
        int crc = crc16(full, 33);
        full[33] = (byte) (crc & 0xFF);   // little-endian (NKey spec)
        full[34] = (byte) (crc >> 8);
        return base32Encode(full);
    }

    private static byte[] base32Decode(String input) {
        int outputLen = input.length() * 5 / 8;
        byte[] output = new byte[outputLen];
        int bits = 0, value = 0, outIdx = 0;
        for (char c : input.toUpperCase().toCharArray()) {
            int idx = BASE32_ALPHA.indexOf(c);
            if (idx < 0) continue;
            value = (value << 5) | idx;
            bits += 5;
            if (bits >= 8) {
                output[outIdx++] = (byte) ((value >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return output;
    }

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int bits = 0, value = 0;
        for (byte b : data) {
            value = (value << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHA.charAt((value >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHA.charAt((value << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private static int crc16(byte[] data, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
            }
        }
        return crc & 0xFFFF;
    }
}
