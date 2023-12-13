import org.apache.commons.mail.Email
import org.apache.commons.mail.SimpleEmail
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.keyprovider.FileKeyPairProvider
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.fs.SftpFileSystem
import org.apache.sshd.sftp.client.fs.SftpFileSystemProvider
import org.apache.sshd.sftp.client.impl.DefaultSftpClientFactory

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyPair
import java.util.stream.Collectors

/**
 * Script creates SFTP connection to Excelsior and pulls down course registration files.
 * The file is then removed from the Excelsior server.
 * A email is sent to users in the registrar at Utica letting them know a file is available for review.
 *
 * This can only be executed on the jobsub server.
 *
 * @author Michael Stockman
 */
@GrabConfig(systemClassLoader = true)
@Grab(group='org.apache.sshd', module='sshd-sftp', version='2.10.0')
@Grab(group='org.slf4j', module='slf4j-nop', version='1.7.32')
@Grab(group='org.apache.commons', module='commons-email', version='1.5')

def excelsiorFiles = []
def excelsiorPath = '/u03/dataload/student/registrar/excelsior'
def sendTo = args[0].toString().split(',')
sftp(sftpProps()) { SftpFileSystem sftpFs ->
    excelsiorFiles =
            Files.list(sftpFs.defaultDir)
                    .filter(p -> p.fileName.toString().toUpperCase().startsWith('NUR'))
                    .collect(Collectors.toList())

    excelsiorFiles.each {
        Path excelsiorFile = Files.copy(it,Paths.get(excelsiorPath,it.fileName.toString()), StandardCopyOption.REPLACE_EXISTING)
        Files.setPosixFilePermissions(excelsiorFile, PosixFilePermissions.fromString('rw-rw-r--'))
        sendEmail(sendTo,excelsiorFile)
        // Finally delete the file from Excelsior server
        Files.delete(it)
    }
}

/**
 * Sends email alerting users that new Excelsior file has been downloaded
 * Does not actually contain the file as attachment because of sensitive data
 * @param sendTo
 * @param path
 */
void sendEmail(String[] sendTo, Path file) {
    Email email = new SimpleEmail()
    email.setHostName('localhost')
    email.setSmtpPort(25)
    email.setFrom('noreply@utica.edu')
    email.setSubject('New Excelsior course registration file ' + file.fileName.toString())
    email.setMsg('New Excelsior course registration file available to download. ' + file.fileName.toString())
    email.addTo(sendTo)
    email.send()
}

/**
 * Creates SFTP connection and provides SFTP Filesystem object within closure
 * Connection will close at the end  of closure
 * @param c Closure
 * @return
 */
def sftp(Properties properties,Closure c) {
    SshClient client = SshClient.setUpDefaultClient()
    client.start()
    client.connect(properties.'sftp.user',properties.'sftp.host',properties.'sftp.port' as int).verify().getClientSession().withCloseable { ClientSession clientSession ->
        FileKeyPairProvider fileKeyPairProvider = new FileKeyPairProvider(Paths.get(properties.'sftp.private-key'))
        Iterable<KeyPair> keyPairs = fileKeyPairProvider.loadKeys(clientSession)
        clientSession.addPublicKeyIdentity(keyPairs.iterator().next())
        clientSession.auth().verify()
        DefaultSftpClientFactory.INSTANCE.createSftpClient(clientSession).with { SftpClient sftpClient ->
            SftpFileSystemProvider provider = new SftpFileSystemProvider(client)
            provider.newFileSystem(sftpClient.session).withCloseable(c)
        }
    }
    client.stop()
}

/**
 * Sftp credentials
 * @return
 */
def sftpProps() {
    def properties = new Properties()
    Paths.get(System.properties.'user.home','.credentials','excelsiorSftp.properties').withInputStream {
        properties.load(it)
    }
    return properties
}