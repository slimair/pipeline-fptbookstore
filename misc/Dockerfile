FROM mcr.microsoft.com/dotnet/aspnet:5.0 AS base
WORKDIR /app
EXPOSE 5000
EXPOSE 5003

FROM mcr.microsoft.com/dotnet/sdk:5.0 AS build
WORKDIR /src
COPY ["code/FptBookStore/FptBookStore.csproj","FptBookStore/"]
RUN dotnet restore FptBookStore/FptBookStore.csproj

COPY ./code .
WORKDIR /src/FptBookStore   
RUN dotnet build "FptBookStore.csproj" -c Release -o /app
# RUN dotnet tool install --global dotnet-ef
# RUN dotnet ef database update

FROM build AS publish
RUN dotnet publish "FptBookStore.csproj" -c Release -o /app

FROM base AS final
WORKDIR /app
COPY --from=publish /app .
ENTRYPOINT ["dotnet", "FptBookStore.dll"]