package local.bookdoctorc;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import androidx.core.content.FileProvider;
import java.io.FileNotFoundException;

/** FileDoctorC stages the file in one read before showing the device chooser. */
public final class ModuleTransferProvider extends FileProvider {
    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Migration handoff is read-only");
        ParcelFileDescriptor descriptor = super.openFile(uri, mode);
        // An open descriptor keeps the payload readable after its cache entry is unlinked.
        super.delete(uri, null, null);
        return descriptor;
    }
}
