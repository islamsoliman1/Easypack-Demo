# Catalog designs and previews

Models and parts accept design uploads when the user has file-management
permission. Editing a model's files also requires catalog-management permission.
Each file is limited to 10 MiB and is stored with the existing authenticated
attachment service.

When creating or editing a record, choose any of:

- Original CAD file: `.sldprt`, `.sldasm`, `.slddrw`, `.step` or `.stp`.
- Browser export: `.stl` or self-contained `.glb` (glTF 2.0).
- Card image: `.png`, `.jpg` or `.jpeg`.

Export the complete SolidWorks part/assembly as STL for browser viewing. Native
SolidWorks and STEP files are retained for download; they are not automatically
converted into browser geometry. GLB files must include their buffers and
textures and must not require Draco/KTX compression decoders.

The card uses the uploaded image when available, otherwise it creates a static
thumbnail from the browser export. Open the design to rotate, pan and zoom; when
both image and geometry exist, use the 3D button to open the geometry.

Machines inherit their model's image and design. A machine's own files override
the matching representation. Updating or deleting a model preview is reflected
on its machines after the affected views refresh. Existing PDF/image attachments
remain usable.

The catalog supports searching codes, names and descriptions. Type into the
kind, shape, model and supported part selectors to filter saved choices; kind and
shape continue using the existing validated manufacturing categories.

Migration V103 adds the MODEL file target and ATTACHMENT/DESIGN/PREVIEW purposes
without removing existing files. No files are made public; reads and mutations
continue to check the current account's record access and permissions.

## Demo edition

The visitor account retains read access to permitted designs but no `files.manage`
permission. Upload/delete controls stay hidden and the API rejects these mutations.
No production assets or database contents are copied into this repository.
