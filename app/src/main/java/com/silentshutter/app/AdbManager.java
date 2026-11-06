package com.silentshutter.app;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

/**
 * Holds the app's ADB identity (RSA key + self-signed certificate), which the phone's
 * wireless debugging remembers after the one-time pairing.
 */
public final class AdbManager extends AbsAdbConnectionManager {
    private static AdbManager sInstance;

    private final PrivateKey mPrivateKey;
    private final Certificate mCertificate;

    public static synchronized AdbManager get(@NonNull Context context) throws Exception {
        if (sInstance == null) {
            sInstance = new AdbManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(15, java.util.concurrent.TimeUnit.SECONDS);
        File keyFile = new File(context.getFilesDir(), "adb_key.pk8");
        File certFile = new File(context.getFilesDir(), "adb_cert.der");
        PrivateKey key = null;
        Certificate cert = null;
        if (keyFile.exists() && certFile.exists()) {
            try {
                key = KeyFactory.getInstance("RSA").generatePrivate(
                        new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
                cert = CertificateFactory.getInstance("X.509").generateCertificate(
                        new ByteArrayInputStream(Files.readAllBytes(certFile.toPath())));
            } catch (Exception e) {
                key = null;
                cert = null;
            }
        }
        if (key == null || cert == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            X500Name name = new X500Name("CN=SilentShutter");
            long now = System.currentTimeMillis();
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name, BigInteger.valueOf(now), new Date(now - 86_400_000L),
                    new Date(now + 20L * 365 * 86_400_000L), name, pair.getPublic());
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate());
            cert = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
            key = pair.getPrivate();
            write(keyFile, key.getEncoded());
            write(certFile, cert.getEncoded());
        }
        mPrivateKey = key;
        mCertificate = cert;
    }

    private static void write(File file, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    @NonNull
    @Override
    protected PrivateKey getPrivateKey() {
        return mPrivateKey;
    }

    @NonNull
    @Override
    protected Certificate getCertificate() {
        return mCertificate;
    }

    @NonNull
    @Override
    protected String getDeviceName() {
        return "SilentShutter";
    }
}
