package com.spotify.hadoop.openpgp;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.security.NoSuchProviderException;
import java.security.SecureRandom;

import org.apache.hadoop.conf.Configuration;

import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPEncryptedData;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPBEEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;

public class OpenPgpDecompressor extends StreamDecompressor {
	public OpenPgpDecompressor(Configuration conf) {
		super(conf);
	}

	protected InputStream createInputStream(InputStream in) throws IOException {
		return createInputStream(
			in,
			wantsIntegrityVerification(),
			new DefaultPrivateKeyFactory(),
			getDecryptionPassPhrase());
	}

	// Default protection, for unit tests.
	static InputStream createInputStream(InputStream in, boolean verifySign, PrivateKeyFactory keyFactory, String passPhrase) throws IOException {
		if (verifySign)
			throw new UnsupportedOperationException("Message integrity validation not yet implemented.");

		try {
			InputStream ret = getFirstLiteralDataInputStream(in, verifySign, keyFactory, passPhrase);

			if (ret == null) throw new IOException("No OpenPGP literal data found");

			return ret;
		} catch (PGPException ex) {
			throw new IOException(ex);
		} catch (NoSuchProviderException ex) {
			throw new IOException(ex);
		}
	}

	private boolean wantsIntegrityVerification() {
		return getConf().getBoolean("spotify.hadoop.openpgp.integrity.verify", false);
	}

	private File getSecringFile() {
		String path = getConf().get("spotify.hadoop.openpgp.secring.path");

		if (path != null)
			return new File(path);

		return GnuPgUtils.getDefaultSecringFile();
	}

	private String getSecretKeyPassPhrase() {
		return getConf().get("spotify.hadoop.openpgp.decrypt.keyPassPhrase", "");
	}

	private String getDecryptionPassPhrase() {
		return getConf().get("spotify.hadoop.openpgp.decrypt.passPhrase",
			getConf().get("spotify.hadoop.openpgp.encrypt.passPhrase", ""));
	}

	private PGPPrivateKey getPrivateKey(long id) {
		try {
			PGPSecretKeyRingCollection col = GnuPgUtils.createSecretKeyRingCollection(getSecringFile());

			return GnuPgUtils.getPrivateKey(col, id, getSecretKeyPassPhrase());
		} catch (Exception ex) {
			throw new KeyNotFoundException(ex);
		}
	}

	private static InputStream getFirstLiteralDataInputStream(InputStream in, boolean verifySign, PrivateKeyFactory keyFactory, String passPhrase) throws IOException, PGPException, NoSuchProviderException {
		PGPObjectFactory pof = new PGPObjectFactory(in);

		Object po;

		for (;;) {
			po = pof.nextObject();

			if (po == null) {
				break;
			} else if (po instanceof PGPCompressedData) {
				InputStream ret = getFirstLiteralDataInputStream(((PGPCompressedData) po).getDataStream(), verifySign, keyFactory, passPhrase);

				if (ret != null) return ret;
			} else if (po instanceof PGPLiteralData) {
				return ((PGPLiteralData) po).getDataStream();
			} else if (po instanceof PGPEncryptedDataList) {
				for (Iterator<PGPEncryptedData> it = ((PGPEncryptedDataList) po).getEncryptedDataObjects(); it.hasNext();) {
					PGPEncryptedData ped = it.next();

					if (ped instanceof PGPPublicKeyEncryptedData) {
						PGPPublicKeyEncryptedData pked = (PGPPublicKeyEncryptedData) ped;

						InputStream ret = getFirstLiteralDataInputStream(pked.getDataStream(keyFactory.getPrivateKey(pked.getKeyID()), "BC"), verifySign, keyFactory, passPhrase);

						if (ret != null) return ret;
						// TODO: To verify integrity,
						//       we need to keep the
						//       pked reference.
					} else if (ped instanceof PGPPBEEncryptedData) {
						PGPPBEEncryptedData pped = (PGPPBEEncryptedData) ped;

						InputStream ret = getFirstLiteralDataInputStream(pped.getDataStream(passPhrase.toCharArray(), "BC"), verifySign, keyFactory, passPhrase);

						if (ret != null) return ret;
					} else {
						throw new IOException("Unknown encryption packet");
					}
				}
			} else {
				// TODO: BC doesn't skip the contents of all packet
				// types, so this will probably fail for some
				// files.
			}
		}

		return null;
	}

	interface PrivateKeyFactory {
		public PGPPrivateKey getPrivateKey(long id);
	}

	private class DefaultPrivateKeyFactory implements PrivateKeyFactory {
		public PGPPrivateKey getPrivateKey(long id) {
			return OpenPgpDecompressor.this.getPrivateKey(id);
		}
	}
}
