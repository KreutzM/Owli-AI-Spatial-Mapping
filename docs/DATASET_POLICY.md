# Dataset and Recording Policy

Real-world camera, depth, IMU, GNSS, and ARCore recordings may be sensitive.

Before adding or uploading a recording:

- avoid identifiable people, faces, screens, documents, number plates, private homes, and confidential workplaces;
- disable geospatial features unless location is explicitly required and approved;
- remove audio;
- record the device model, Android version, ARCore version, app SHA, mounting position, and capture purpose;
- keep small synthetic fixtures in Git;
- keep larger or real-world datasets in access-controlled artifacts or releases;
- document retention and deletion;
- never put raw personal/location data into public CI artifacts.

`test-data/recorded/` is ignored by default. Its README remains versioned.
