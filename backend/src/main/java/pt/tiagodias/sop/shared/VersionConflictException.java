package pt.tiagodias.sop.shared;

public class VersionConflictException extends RuntimeException {
    private final long currentVersion;
    public VersionConflictException(long currentVersion) {
        super("Versão desatualizada. Consulta novamente a revisão antes de alterar.");
        this.currentVersion=currentVersion;
    }
    public long getCurrentVersion() { return currentVersion; }
}
