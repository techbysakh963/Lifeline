// Placeholder shared library: after linking, CMake overwrites it with a real executable (proot, its loader), so AGP
// packages the program as a lib*.so entry that Android extracts to nativeLibraryDir, where apps may run programs.
void lifeline_executable_carrier(void) {}
