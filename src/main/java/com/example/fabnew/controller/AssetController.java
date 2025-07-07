package com.example.fabnew.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.hyperledger.fabric.client.*;
import org.hyperledger.fabric.client.identity.*;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/assets") // Base path for your asset APIs
public class AssetController {

    // --- Configuration Constants ---
    private static final String OVERRIDE_AUTH = "peer0.org1.example.com";
    private static final String CHANNEL_NAME = "mychannel";
    private static final String CHAINCODE_NAME = "basic";

    @Value("${fabric.mspId}")
    private String mspId;

    // This will now correctly use the environment variable or the default
    @Value("${FABRIC_PEERENDPOINT:grpcs://34.100.239.129:7051}")
    private String peerEndpoint;

    // Paths to your crypto materials (relative to project root where certs are copied)
    private static final Path CRYPTO_PATH = Paths.get("fabric-network-certs", "test-network", "organizations", "peerOrganizations", "org1.example.com");
    private static final Path KEY_DIR_PATH = CRYPTO_PATH.resolve(Paths.get("users", "User1@org1.example.com", "msp", "keystore"));
    private static final Path CERT_DIR_PATH = CRYPTO_PATH.resolve(Paths.get("users", "User1@org1.example.com", "msp", "signcerts"));
    private static final Path TLS_CERT_PATH = CRYPTO_PATH.resolve(Paths.get("peers", "peer0.org1.example.com", "tls", "ca.crt"));

    private Gateway gateway;
    // Declare channel as a class-level field
    private ManagedChannel channel;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @PostConstruct
    public void init() throws Exception {
        try {
            // Initialize the class-level channel variable
            this.channel = newGrpcConnection();

            this.gateway = Gateway.newInstance()
                    .identity(newIdentity())
                    .signer(newSigner())
                    .connection(this.channel) // Use the class-level channel
                    .evaluateOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                    .endorseOptions(options -> options.withDeadlineAfter(15, TimeUnit.SECONDS))
                    .submitOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                    .commitStatusOptions(options -> options.withDeadlineAfter(1, TimeUnit.MINUTES))
                    .connect();
        } catch (Exception e) {
            System.err.println("Failed to connect to Fabric Gateway: " + e.getMessage());
            e.printStackTrace();
            if (this.channel != null) { // Use 'this.channel' to refer to the class member
                this.channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
            throw e;
        }
    }

    @PostConstruct
    public void validateConfig() {
        System.out.println("fabric.mspId: " + mspId);
        System.out.println("fabric.peerEndpoint: " + peerEndpoint);
        if (mspId == null || mspId.isEmpty()) {
            throw new IllegalStateException("fabric.mspId must be configured");
        }
        if (peerEndpoint == null || peerEndpoint.isEmpty()) {
            throw new IllegalStateException("fabric.peerEndpoint must be configured");
        }
        // This check is still valid, but the parsing logic in newGrpcConnection handles the scheme
        if (!peerEndpoint.startsWith("grpc://") && !peerEndpoint.startsWith("grpcs://")) {
            throw new IllegalStateException("peerEndpoint must start with grpc:// or grpcs://");
        }
    }

    // Helper method to create a new gRPC connection
    private ManagedChannel newGrpcConnection() throws IOException {
        System.out.println("Creating gRPC connection to: " + peerEndpoint);
        System.out.println("Using TLS cert from: " + TLS_CERT_PATH.toAbsolutePath());

        if (!Files.exists(TLS_CERT_PATH)) {
            throw new IOException("TLS certificate file not found at: " + TLS_CERT_PATH.toAbsolutePath());
        }

        // Parse the peerEndpoint to extract host and port
        URI uri = URI.create(peerEndpoint);
        String host = uri.getHost();
        int port = uri.getPort();
        boolean useTls = uri.getScheme().equalsIgnoreCase("grpcs");

        if (host == null || port == -1) {
            throw new IllegalArgumentException("Invalid peerEndpoint format: " + peerEndpoint + ". Expected format like grpcs://host:port");
        }

        NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(host, port)
                .overrideAuthority(OVERRIDE_AUTH);

        if (useTls) {
            SslContext sslContext = GrpcSslContexts.forClient()
                    .trustManager(TLS_CERT_PATH.toFile())
                    .build();
            channelBuilder.sslContext(sslContext);
        } else {
            channelBuilder.usePlaintext(); // For grpc:// (non-TLS) connections
        }

        return channelBuilder.build();
    }

    // Helper method to create a new client identity
    private Identity newIdentity() throws IOException, CertificateException {
        try (Reader certReader = Files.newBufferedReader(getFirstFile(CERT_DIR_PATH))) {
            X509Certificate certificate = Identities.readX509Certificate(certReader);
            return new X509Identity(mspId, certificate);
        }
    }

    // Helper method to create a new signer
    private Signer newSigner() throws IOException, InvalidKeyException {
        try (Reader keyReader = Files.newBufferedReader(getFirstFile(KEY_DIR_PATH))) {
            java.security.PrivateKey privateKey = Identities.readPrivateKey(keyReader);
            return Signers.newPrivateKeySigner(privateKey);
        }
    }

    // Helper method to get the first file path from a directory
    private Path getFirstFile(final Path directoryPath) throws IOException {
        try (Stream<Path> walk = Files.walk(directoryPath, 1)) {
            return walk.filter(Files::isRegularFile)
                    .findFirst()
                    .orElseThrow(() -> new IOException(String.format("Directory '%s' contains no files", directoryPath)));
        }
    }

    private String prettyJson(final byte[] json) {
        return prettyJson(new String(json, StandardCharsets.UTF_8));
    }

    private String prettyJson(final String json) {
        var parsedJson = JsonParser.parseString(json);
        return gson.toJson(parsedJson);
    }

    // --- REST Endpoints ---

    @GetMapping("/all")
    public String getAllAssets() {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            byte[] result = contract.evaluateTransaction("GetAllAssets");
            return prettyJson(result);
        } catch (GatewayException e) {
            System.err.println("Error getting all assets: " + e.getMessage());
            e.printStackTrace();
            if (e.getCause() instanceof StatusRuntimeException) {
                StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
                return "Error getting all assets: gRPC Status " + sre.getStatus().getCode() + " - " + sre.getStatus().getDescription();
            }
            return "Error getting all assets: " + e.getMessage();
        }
    }

    @PostMapping("/create")
    public String createAsset(@RequestBody AssetCreationRequest request) {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            contract.submitTransaction("CreateAsset", request.assetID, request.color, String.valueOf(request.size), request.owner, String.valueOf(request.appraisedValue));
            return "Asset " + request.assetID + " created successfully!";
        } catch (GatewayException | CommitException e) {
            System.err.println("Error creating asset: " + e.getMessage());
            e.printStackTrace();
            if (e instanceof EndorseException) {
                EndorseException ee = (EndorseException) e;
                return "Error endorsing transaction: " + ee.getMessage() + " Details: " + ee.getDetails();
            } else if (e instanceof SubmitException) {
                SubmitException se = (SubmitException) e;
                return "Error submitting transaction: " + se.getMessage();
            } else if (e instanceof CommitStatusException) {
                CommitStatusException cse = (CommitStatusException) e;
                return "Error getting commit status: " + cse.getMessage() + " Status Code: " + cse.getStatus().getCode(); // Corrected to getCode()
            } else if (e instanceof CommitException) {
                CommitException ce = (CommitException) e;
                return "Error committing transaction: " + ce.getMessage() + " Status Code: " + ce.getCode();
            }
            return "Error creating asset: " + e.getMessage();
        }
    }

    @GetMapping("/{assetId}")
    public String readAsset(@PathVariable String assetId) {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            byte[] result = contract.evaluateTransaction("ReadAsset", assetId);
            return prettyJson(result);
        } catch (GatewayException e) {
            System.err.println("Error reading asset " + assetId + ": " + e.getMessage());
            e.printStackTrace();
            if (e.getCause() instanceof StatusRuntimeException) {
                StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
                return "Error reading asset " + assetId + ": gRPC Status " + sre.getStatus().getCode() + " - " + sre.getStatus().getDescription();
            }
            return "Error reading asset " + assetId + ": " + e.getMessage();
        }
    }

    @PutMapping("/update") // Using PUT for updates
    public String updateAsset(@RequestBody AssetCreationRequest request) {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            // Assuming chaincode's UpdateAsset takes: assetID, color, size, owner, appraisedValue
            contract.submitTransaction("UpdateAsset", request.assetID, request.color, String.valueOf(request.size), request.owner, String.valueOf(request.appraisedValue));
            return "Asset " + request.assetID + " updated successfully!";
        } catch (GatewayException | CommitException e) {
            System.err.println("Error updating asset: " + e.getMessage());
            e.printStackTrace();
            // Detailed error handling for Fabric exceptions
            if (e instanceof EndorseException) {
                EndorseException ee = (EndorseException) e;
                return "Error endorsing transaction: " + ee.getMessage() + " Details: " + ee.getDetails();
            } else if (e instanceof SubmitException) {
                SubmitException se = (SubmitException) e;
                return "Error submitting transaction: " + se.getMessage();
            } else if (e instanceof CommitStatusException) {
                CommitStatusException cse = (CommitStatusException) e;
                return "Error getting commit status: " + cse.getMessage() + " Status Code: " + cse.getStatus().getCode();
            } else if (e instanceof CommitException) {
                CommitException ce = (CommitException) e;
                return "Error committing transaction: " + ce.getMessage() + " Status Code: " + ce.getCode();
            }
            return "Error updating asset: " + e.getMessage();
        }
    }

    @DeleteMapping("/{assetId}") // Using DELETE for deletion
    public String deleteAsset(@PathVariable String assetId) {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            // Assuming chaincode's DeleteAsset takes: assetID
            contract.submitTransaction("DeleteAsset", assetId);
            return "Asset " + assetId + " deleted successfully!";
        } catch (GatewayException | CommitException e) {
            System.err.println("Error deleting asset " + assetId + ": " + e.getMessage());
            e.printStackTrace();
            // Detailed error handling for Fabric exceptions
            if (e instanceof EndorseException) {
                EndorseException ee = (EndorseException) e;
                return "Error endorsing transaction: " + ee.getMessage() + " Details: " + ee.getDetails();
            } else if (e instanceof SubmitException) {
                SubmitException se = (SubmitException) e;
                return "Error submitting transaction: " + se.getMessage();
            } else if (e instanceof CommitStatusException) {
                CommitStatusException cse = (CommitStatusException) e;
                return "Error getting commit status: " + cse.getMessage() + " Status Code: " + cse.getStatus().getCode();
            } else if (e instanceof CommitException) {
                CommitException ce = (CommitException) e;
                return "Error committing transaction: " + ce.getMessage() + " Status Code: " + ce.getCode();
            }
            return "Error deleting asset " + assetId + ": " + e.getMessage();
        }
    }


    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                System.err.println("Failed to shut down gRPC channel: " + e.getMessage());
            }
        }
    }

    // A simple DTO for incoming JSON (createAsset example)
    static class AssetCreationRequest {
        public String assetID;
        public String color;
        public int size;
        public String owner;
        public int appraisedValue;
    }
}
