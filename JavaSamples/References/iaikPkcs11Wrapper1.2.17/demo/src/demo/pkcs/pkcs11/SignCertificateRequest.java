/* Copyright  (c) 2002 Graz University of Technology. All rights reserved.
 *
 * Redistribution and use in  source and binary forms, with or without 
 * modification, are permitted  provided that the following conditions are met:
 *
 * 1. Redistributions of  source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in  binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *  
 * 3. The end-user documentation included with the redistribution, if any, must
 *    include the following acknowledgment:
 * 
 *    "This product includes software developed by IAIK of Graz University of
 *     Technology."
 * 
 *    Alternately, this acknowledgment may appear in the software itself, if 
 *    and wherever such third-party acknowledgments normally appear.
 *  
 * 4. The names "Graz University of Technology" and "IAIK of Graz University of
 *    Technology" must not be used to endorse or promote products derived from 
 *    this software without prior written permission.
 *  
 * 5. Products derived from this software may not be called 
 *    "IAIK PKCS Wrapper", nor may "IAIK" appear in their name, without prior 
 *    written permission of Graz University of Technology.
 *  
 *  THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESSED OR IMPLIED
 *  WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 *  WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 *  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE LICENSOR BE
 *  LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY,
 *  OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
 *  OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 *  ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 *  OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 *  OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *  POSSIBILITY  OF SUCH DAMAGE.
 */

package demo.pkcs.pkcs11;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.security.Signature;
import java.util.Arrays;
import java.util.List;

import iaik.asn1.structures.AlgorithmID;
import iaik.asn1.structures.Name;
import iaik.pkcs.pkcs10.CertificateRequest;
import iaik.pkcs.pkcs11.Mechanism;
import iaik.pkcs.pkcs11.MechanismInfo;
import iaik.pkcs.pkcs11.Module;
import iaik.pkcs.pkcs11.Session;
import iaik.pkcs.pkcs11.Token;
import iaik.pkcs.pkcs11.objects.PrivateKey;
import iaik.pkcs.pkcs11.objects.RSAPrivateKey;
import iaik.pkcs.pkcs11.objects.X509PublicKeyCertificate;
import iaik.utils.PemOutputStream;
import iaik.utils.RFC2253NameParser;
import iaik.x509.X509Certificate;



/**
 * Signs a PKCS#10 certificate request using a token. The actual PKCS#10
 * specific operations are in the last section of this demo.
 * The hash is calculated outside the token. This implementation just uses raw
 * RSA.
 * This example works as follows. In general, the 
 * <code>CertificateRequest</code> class from the IAIK JCE toolkit works with 
 * JCA private keys only. To get it to work with the wrapper, we need a special
 * <code>AlgorithmID</code> class (see {@link demo.pkcs.pkcs11.AlgorithmIDAdapter }) 
 * which provides a special <code>Signature</code> engine object 
 * (see {@link demo.pkcs.pkcs11.PKCS11SignatureEngine }) to the certificate 
 * request object. This signature engine only accepts keys of type 
 * {@link demo.pkcs.pkcs11.TokenPrivateKey }, which just wraps PKCS#11 key 
 * objects. All these helper classes are required if you want to sign a 
 * certificate request with the PKCS#11 wrapper. If you use the IAIK PKCS#11
 * provider, everything is much easier. In this case, you do not need any
 * of these helper classes.
 *  
 *
 * @author <a href="mailto:Karl.Scheibelhofer@iaik.at"> Karl Scheibelhofer </a>
 * @version 0.1
 * @invariants
 */
public class SignCertificateRequest {

  static PrintWriter output_;

  static BufferedReader input_;

  static {
    try {
      //output_ = new PrintWriter(new FileWriter("GetInfo_output.txt"), true);
      output_ = new PrintWriter(System.out, true);
      input_ = new BufferedReader(new InputStreamReader(System.in));
    } catch (Throwable thr) {
      thr.printStackTrace();
      output_ = new PrintWriter(System.out, true);
      input_ = new BufferedReader(new InputStreamReader(System.in));
    }
  }

  public static void main(String[] args) {
    if (args.length != 4) {
      printUsage();
      System.exit(1);
    }

    try {

      Module pkcs11Module = Module.getInstance(args[0]);
      pkcs11Module.initialize(null);

      Token token = Util.selectToken(pkcs11Module, output_, input_);
      if (token == null) {
        output_.println("We have no token to proceed. Finished.");
        output_.flush();
        System.exit(0);
      }

      List supportedMechanisms = Arrays.asList(token.getMechanismList());
      if (!supportedMechanisms.contains(Mechanism.RSA_PKCS)) {
        output_.print("This token does not support raw RSA signing!");
        output_.flush();
        System.exit(0);
      } else {
        MechanismInfo rsaMechanismInfo = token.getMechanismInfo(Mechanism.RSA_PKCS);
        if (!rsaMechanismInfo.isSign()) {
          output_.print("This token does not support RSA signing according to PKCS!");
          output_.flush();
          System.exit(0);
        }
      }

      Session session = Util.openAuthorizedSession(token, Token.SessionReadWriteBehavior.RO_SESSION, output_, input_);

      // first we search for private RSA keys that we can use for signing
      RSAPrivateKey privateSignatureKeyTemplate = new RSAPrivateKey();
      privateSignatureKeyTemplate.getSign().setBooleanValue(Boolean.TRUE);

      KeyAndCertificate selectedSignatureKeyAndCertificate =
          Util.selectKeyAndCertificate(session, privateSignatureKeyTemplate, output_, input_);
      if (selectedSignatureKeyAndCertificate == null) {
        output_.println("We have no signature key to proceed. Finished.");
        output_.flush();
        System.exit(0);
      }

      PrivateKey selectedSignatureKey = (PrivateKey) selectedSignatureKeyAndCertificate.getKey();
      X509PublicKeyCertificate pkcs11SignerCertificate = selectedSignatureKeyAndCertificate.getCertificate();
      X509Certificate signerCertificate = (pkcs11SignerCertificate != null)
                                          ? new X509Certificate(pkcs11SignerCertificate.getValue().getByteArrayValue())
                                          : null;

      // here the interesting code starts

      output_.println("################################################################################");
      output_.println("generating and signing PKCS#10 certificate request");

      InputStream publicKeyInputStream = new FileInputStream(args[1]);
      iaik.security.rsa.RSAPublicKey subjectPublicKey = new iaik.security.rsa.RSAPublicKey(publicKeyInputStream);

      RFC2253NameParser subjectNameParser = new RFC2253NameParser(args[2]);
      Name subjectName = subjectNameParser.parse();

      CertificateRequest certificateRequest = new CertificateRequest(subjectPublicKey, subjectName);

      Signature tokenSignatureEngine =
          new PKCS11SignatureEngine("SHA1withRSA", session, Mechanism.RSA_PKCS, AlgorithmID.sha1);
      AlgorithmIDAdapter pkcs11Sha1RSASignatureAlgorithmID = new AlgorithmIDAdapter(AlgorithmID.sha1WithRSAEncryption);
      pkcs11Sha1RSASignatureAlgorithmID.setSignatureInstance(tokenSignatureEngine);

      java.security.PrivateKey tokenSignatureKey = new TokenPrivateKey(selectedSignatureKey);

      output_.print("signing certificate request... ");
      certificateRequest.sign(pkcs11Sha1RSASignatureAlgorithmID , tokenSignatureKey);
      output_.println("finished");

      output_.print("writing certificate request to file \"");
      output_.print(args[3]);
      output_.print("\"... ");
      String firstLine = "-----BEGIN NEW CERTIFICATE REQUEST-----";
      String lastLine =  "-----END NEW CERTIFICATE REQUEST-----";
      OutputStream certificateStream = new PemOutputStream(new FileOutputStream(args[3]), firstLine, lastLine);
      certificateRequest.writeTo(certificateStream);
      certificateStream.flush();
      certificateStream.close();
      output_.println("finished");

      output_.println("################################################################################");

      session.closeSession();
      pkcs11Module.finalize(null);

    } catch (Throwable thr) {
      thr.printStackTrace();
    } finally {
      output_.close();
    }
  }

  public static void printUsage() {
    output_.println("Usage: SignCertificateRequest <PKCS#11 module> <DER-encoded X.509 public RSA key file> <RFC2253 subject name> <PEM-encoded PKCS#10 certificate request output file>");
    output_.println(" e.g.: SignCertificateRequest pk2priv.dll publicKey.xpk \"CN=Karl Scheibelhofer,O=IAIK,C=AT,EMAIL=karl.scheibelhofer@iaik.at\" certificateRequest.p10");
    output_.println("The given DLL must be in the search path of the system.");
  }

}