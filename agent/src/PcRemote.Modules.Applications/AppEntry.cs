namespace PcRemote.Modules.Applications;

/// <summary>
/// Aplicación instalada descubierta por alguna de las 3 fuentes.
/// Id es único (source:key) para permitir launch estable desde el móvil.
/// </summary>
public record AppEntry(string Id, string Name, string Source, string Launch);
