import unittest
from apk_release_guard import parse_certificate, parse_package, evaluate

class ReleaseGuardTest(unittest.TestCase):
    def sample(self):
        return dict(package='com.example.riskscanner', versionCode=20, certificateSha256='a'*64)
    def test_exact_verified_certificate(self):
        self.assertEqual(parse_certificate('Signer #1 certificate SHA-256 digest: '+ 'A'*64), 'a'*64)
        for value in ['', 'certificate: bad', 'Signer #1 certificate SHA-256 digest: '+ 'a'*63,
                      '\n'.join('Signer #1 certificate SHA-256 digest: '+ 'a'*64 for _ in range(2))]:
            with self.assertRaises(ValueError): parse_certificate(value)
    def test_package_metadata(self):
        self.assertEqual(parse_package("package: name='com.example.riskscanner' versionCode='21' versionName='1.18.2'"),
                         dict(package='com.example.riskscanner', versionCode=21))
        with self.assertRaises(ValueError): parse_package('bad metadata')
    def test_same_signer_forward_update(self):
        old=self.sample(); new={**old,'versionCode':21}
        self.assertTrue(evaluate(old,new)['compatible'])
    def test_changed_signer_never_published(self):
        old=self.sample(); new={**old,'versionCode':21,'certificateSha256':'b'*64}
        self.assertEqual(evaluate(old,new)['reasons'],['signing_certificate_mismatch'])
    def test_wrong_package_and_downgrade_never_published(self):
        old=self.sample(); new={**old,'package':'org.unrelated','versionCode':19}
        self.assertEqual(evaluate(old,new)['reasons'],['package_mismatch','version_not_increased'])
if __name__=='__main__': unittest.main()
