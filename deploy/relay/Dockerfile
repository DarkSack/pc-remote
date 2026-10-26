# Relay de PC Remote. La imagen solo copia lo publicado (publicar.ps1): en este PC,
# restaurar paquetes dentro de docker build falla por el proxy que inspecciona HTTPS.
FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY app/ .
USER $APP_UID
ENV RELAY_URLS=http://0.0.0.0:8080 \
    DOTNET_gcServer=1
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD ["dotnet", "PcRemote.Relay.dll", "--healthcheck"]
ENTRYPOINT ["dotnet", "PcRemote.Relay.dll"]
