package com.example.fabnew.controller;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
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

/**
 * AssetController is a Spring Boot REST controller that provides API endpoints
 * for interacting with a Hyperledger Fabric blockchain network to manage assets.
 * It uses the Hyperledger Fabric Gateway Java SDK to submit transactions and
 * evaluate queries against a chaincode (smart contract).
 */
@RestController // Marks this class as a Spring REST controller
@RequestMapping("/api/assets") // Base path for all REST endpoints in this controller (e.g., /api/assets/all)
public class AssetController {

    // --- Configuration Constants ---
    // The authority to override for gRPC connections, typically the peer's hostname in its TLS certificate.
    private static final String OVERRIDE_AUTH = "peer0.org1.example.com";
    // The name of the Hyperledger Fabric channel to connect to.
    private static final String CHANNEL_NAME = "mychannel";
    // The name of the chaincode (smart contract) deployed on the channel.
    private static final String CHAINCODE_NAME = "basic";

    // Injects the MSP ID from Spring application properties (e.g., application.properties).
    @Value("${fabric.mspId}")
    private String mspId;

    // Injects the peer endpoint from Spring application properties.
    @Value("${fabric.peerEndpoint}")
    private String peerEndpoint;

    // Paths to your crypto materials (relative to the project root where certs are copied).
    // This assumes a specific directory structure for the Fabric network's crypto configuration.
    private static final Path CRYPTO_PATH = Paths.get("fabric-network-certs", "test-network", "organizations", "peerOrganizations", "org1.example.com");
    // Path to the directory containing the user's private key.
    private static final Path KEY_DIR_PATH = CRYPTO_PATH.resolve(Paths.get("users", "User1@org1.example.com", "msp", "keystore"));
    // Path to the directory containing the user's signing certificate.
    private static final Path CERT_DIR_PATH = CRYPTO_PATH.resolve(Paths.get("users", "User1@org1.example.com", "msp", "signcerts"));
    // Path to the TLS CA certificate of the peer, used to establish a secure gRPC connection.
    private static final Path TLS_CERT_PATH = CRYPTO_PATH.resolve(Paths.get("peers", "peer0.org1.example.com", "tls", "ca.crt"));

    // Hyperledger Fabric Gateway instance, the main entry point for interacting with the network.
    private Gateway gateway;
    // gRPC channel used for communication with the Fabric peer. Declared as a class-level field
    // to ensure it can be properly shut down.
    private ManagedChannel channel;
    // Gson instance for pretty-printing JSON responses.
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Initializes the Hyperledger Fabric Gateway connection after the controller
     * bean has been constructed and properties injected. This method sets up
     * the gRPC channel, loads identity and signer, and connects to the Fabric Gateway.
     * It also performs an initial connection test by calling GetAllAssets.
     *
     * @throws Exception if any error occurs during connection setup or initial test.
     */
    @PostConstruct
    public void init() throws Exception {
        try {
            // Initialize the class-level channel variable by creating a new gRPC connection.
            this.channel = newGrpcConnection();

            // Build and connect the Fabric Gateway.
            this.gateway = Gateway.newInstance()
                    .identity(newIdentity()) // Set the client identity (User1's certificate)
                    .signer(newSigner())     // Set the client signer (User1's private key)
                    .connection(this.channel) // Use the established gRPC channel
                    // Configure timeouts for different transaction phases
                    .evaluateOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                    .endorseOptions(options -> options.withDeadlineAfter(15, TimeUnit.SECONDS))
                    .submitOptions(options -> options.withDeadlineAfter(5, TimeUnit.SECONDS))
                    .commitStatusOptions(options -> options.withDeadlineAfter(1, TimeUnit.MINUTES))
                    .connect(); // Establish the connection to the Fabric Gateway

            // Test the connection by evaluating a simple transaction.
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            try {
                contract.evaluateTransaction("GetAllAssets");
                System.out.println("Successfully connected to Fabric network");
            } catch (Exception e) {
                System.err.println("Initial connection test failed: " + e.getMessage());
                throw e; // Re-throw to indicate initialization failure
            }
        } catch (Exception e) {
            System.err.println("Failed to connect to Fabric Gateway: " + e.getMessage());
            e.printStackTrace();
            // Ensure the channel is shut down if initialization fails.
            if (this.channel != null) {
                this.channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
            throw new RuntimeException("Failed to initialize Fabric connection", e);
        }
    }

    /**
     * Validates the configuration properties (mspId and peerEndpoint) after
     * the controller bean has been constructed. This ensures essential
     * configuration values are present before attempting to connect to Fabric.
     *
     * @throws IllegalStateException if any required configuration property is missing or invalid.
     */
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
        // Basic check for endpoint scheme. The newGrpcConnection method handles the parsing.
        if (!peerEndpoint.startsWith("grpc://") && !peerEndpoint.startsWith("grpcs://")) {
            throw new IllegalStateException("peerEndpoint must start with grpc:// or grpcs://");
        }
    }

    /**
     * Creates and configures a new gRPC ManagedChannel for connecting to the Fabric peer.
     * It supports both plaintext (grpc://) and TLS (grpcs://) connections.
     *
     * @return A configured ManagedChannel instance.
     * @throws IOException if the TLS certificate file is not found.
     * @throws IllegalArgumentException if the peerEndpoint format is invalid.
     */
    private ManagedChannel newGrpcConnection() throws IOException {
        System.out.println("Creating gRPC connection to: " + peerEndpoint);
        System.out.println("Using TLS cert from: " + TLS_CERT_PATH.toAbsolutePath());

        // Verify that the TLS certificate file exists.
        if (!Files.exists(TLS_CERT_PATH)) {
            throw new IOException("TLS certificate file not found at: " + TLS_CERT_PATH.toAbsolutePath());
        }

        // Parse the peer endpoint URI to extract host, port, and scheme.
        URI uri = URI.create(peerEndpoint);
        String host = uri.getHost();
        int port = uri.getPort();
        boolean useTls = uri.getScheme().equalsIgnoreCase("grpcs");

        // Validate extracted host and port.
        if (host == null || port == -1) {
            throw new IllegalArgumentException("Invalid peerEndpoint format: " + peerEndpoint + ". Expected format like grpcs://host:port");
        }

        // Build the NettyChannelBuilder with address and override authority.
        NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(host, port)
                .overrideAuthority(OVERRIDE_AUTH) // Important for TLS certificate validation
                .channelType(io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel.class)
                .eventLoopGroup(new io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup())
                .keepAliveTimeout(30, TimeUnit.SECONDS) // Configure gRPC keep-alive settings
                .keepAliveTime(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true);

        // Configure TLS or plaintext based on the URI scheme.
        if (useTls) {
            SslContext sslContext = GrpcSslContexts.forClient()
                    .trustManager(TLS_CERT_PATH.toFile()) // Trust the peer's TLS certificate
                    .build();
            channelBuilder.sslContext(sslContext);
        } else {
            channelBuilder.usePlaintext(); // Use unencrypted connection for grpc://
        }

        return channelBuilder.build();
    }

    /**
     * Creates a new client identity (X.509Identity) from the user's signing certificate.
     * This identity is used by the Fabric Gateway to identify the client.
     *
     * @return An X509Identity instance.
     * @throws IOException if the certificate file cannot be read.
     * @throws CertificateException if the certificate is invalid.
     */
    private Identity newIdentity() throws IOException, CertificateException {
        // Read the X.509 certificate from the specified path.
        try (Reader certReader = Files.newBufferedReader(getFirstFile(CERT_DIR_PATH))) {
            X509Certificate certificate = Identities.readX509Certificate(certReader);
            return new X509Identity(mspId, certificate); // Create identity with MSP ID and certificate
        }
    }

    /**
     * Creates a new signer (PrivateKeySigner) from the user's private key.
     * This signer is used by the Fabric Gateway to digitally sign transactions.
     *
     * @return A Signer instance.
     * @throws IOException if the private key file cannot be read.
     * @throws InvalidKeyException if the private key is invalid.
     */
    private Signer newSigner() throws IOException, InvalidKeyException {
        // Read the private key from the specified path.
        try (Reader keyReader = Files.newBufferedReader(getFirstFile(KEY_DIR_PATH))) {
            java.security.PrivateKey privateKey = Identities.readPrivateKey(keyReader);
            return Signers.newPrivateKeySigner(privateKey); // Create signer with the private key
        }
    }

    /**
     * Helper method to get the path of the first regular file found in a given directory.
     * Used to locate the single certificate or private key file within their respective directories.
     *
     * @param directoryPath The path to the directory to search.
     * @return The Path of the first regular file found.
     * @throws IOException if the directory is empty or does not contain any regular files.
     */
    private Path getFirstFile(final Path directoryPath) throws IOException {
        try (Stream<Path> walk = Files.walk(directoryPath, 1)) { // Walk only the top level of the directory
            return walk.filter(Files::isRegularFile) // Filter for regular files
                    .findFirst() // Get the first one
                    .orElseThrow(() -> new IOException(String.format("Directory '%s' contains no files", directoryPath)));
        }
    }

    /**
     * Converts a byte array containing JSON data into a pretty-printed JSON string.
     *
     * @param json The byte array containing JSON.
     * @return A pretty-printed JSON string.
     */
    private String prettyJson(final byte[] json) {
        return prettyJson(new String(json, StandardCharsets.UTF_8)); // Convert bytes to String first
    }

    /**
     * Converts a JSON string into a pretty-printed JSON string.
     *
     * @param json The JSON string.
     * @return A pretty-printed JSON string.
     */
    private String prettyJson(final String json) {
        var parsedJson = JsonParser.parseString(json); // Parse the JSON string
        return gson.toJson(parsedJson); // Convert parsed JSON object back to pretty-printed string
    }

    // --- REST Endpoints ---

    /**
     * REST endpoint to test the connection to the Hyperledger Fabric network.
     * It attempts to retrieve all assets as a basic connectivity check.
     *
     * @return A success message with the result of GetAllAssets if successful, or an error message.
     */
    @GetMapping("/test-connection")
    public String testConnection() {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME); // Get the network (channel) instance
            Contract contract = network.getContract(CHAINCODE_NAME); // Get the contract (chaincode) instance
            byte[] result = contract.evaluateTransaction("GetAllAssets"); // Evaluate the GetAllAssets transaction
            return "Connection successful! Response: " + prettyJson(result);
        } catch (Exception e) {
            System.err.println("Connection test failed: " + e.getMessage());
            e.printStackTrace();
            return "Connection failed: " + e.getMessage();
        }
    }

    /**
     * REST endpoint to retrieve all assets from the Hyperledger Fabric ledger.
     * This is a read-only operation.
     *
     * @return A pretty-printed JSON string of all assets, or an error message.
     */
    @GetMapping("/all")
    public String getAllAssets() {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            byte[] result = contract.evaluateTransaction("GetAllAssets"); // Evaluate the GetAllAssets transaction
            return prettyJson(result);
        } catch (GatewayException e) {
            System.err.println("Error getting all assets: " + e.getMessage());
            e.printStackTrace();
            // Provide more specific error details if it's a gRPC StatusRuntimeException
            if (e.getCause() instanceof StatusRuntimeException) {
                StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
                return "Error getting all assets: gRPC Status " + sre.getStatus().getCode() + " - " + sre.getStatus().getDescription();
            }
            return "Error getting all assets: " + e.getMessage();
        }
    }

    /**
     * REST endpoint to create a new asset on the Hyperledger Fabric ledger.
     * This is a transaction that modifies the ledger state.
     *
     * @param request An AssetCreationRequest object containing the details of the asset to create.
     * @return A success message if the asset is created, or a detailed error message.
     */
    @PostMapping("/create")
    public String createAsset(@RequestBody AssetCreationRequest request) {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            // Submit the CreateAsset transaction with provided arguments.
            // Note: size and appraisedValue are converted to String as chaincode arguments are typically strings.
            contract.submitTransaction("CreateAsset", request.assetID, request.color, String.valueOf(request.size), request.owner, String.valueOf(request.appraisedValue));
            return "Asset " + request.assetID + " created successfully!";
        } catch (GatewayException | CommitException e) {
            System.err.println("Error creating asset: " + e.getMessage());
            e.printStackTrace();
            // Detailed error handling for various Fabric transaction exceptions.
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
            return "Error creating asset: " + e.getMessage();
        }
    }

    /**
     * REST endpoint to read a specific asset from the Hyperledger Fabric ledger by its ID.
     * This is a read-only operation.
     *
     * @param assetId The ID of the asset to read, extracted from the URL path.
     * @return A pretty-printed JSON string of the asset, or an error message if not found or an error occurs.
     */
    @GetMapping("/{assetId}")
    public String readAsset(@PathVariable String assetId) {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            byte[] result = contract.evaluateTransaction("ReadAsset", assetId); // Evaluate the ReadAsset transaction
            return prettyJson(result);
        } catch (GatewayException e) {
            System.err.println("Error reading asset " + assetId + ": " + e.getMessage());
            e.printStackTrace();
            // Provide more specific error details if it's a gRPC StatusRuntimeException
            if (e.getCause() instanceof StatusRuntimeException) {
                StatusRuntimeException sre = (StatusRuntimeException) e.getCause();
                return "Error reading asset " + assetId + ": gRPC Status " + sre.getStatus().getCode() + " - " + sre.getStatus().getDescription();
            }
            return "Error reading asset " + assetId + ": " + e.getMessage();
        }
    }

    /**
     * REST endpoint to update an existing asset on the Hyperledger Fabric ledger.
     * This is a transaction that modifies the ledger state.
     *
     * @param request An AssetCreationRequest object containing the updated details of the asset.
     * @return A success message if the asset is updated, or a detailed error message.
     */
    @PutMapping("/update") // Using PUT for updates
    public String updateAsset(@RequestBody AssetCreationRequest request) {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            // Submit the UpdateAsset transaction with provided arguments.
            contract.submitTransaction("UpdateAsset", request.assetID, request.color, String.valueOf(request.size), request.owner, String.valueOf(request.appraisedValue));
            return "Asset " + request.assetID + " updated successfully!";
        } catch (GatewayException | CommitException e) {
            System.err.println("Error updating asset: " + e.getMessage());
            e.printStackTrace();
            // Detailed error handling for various Fabric transaction exceptions.
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

    /**
     * REST endpoint to delete an asset from the Hyperledger Fabric ledger by its ID.
     * This is a transaction that modifies the ledger state.
     *
     * @param assetId The ID of the asset to delete, extracted from the URL path.
     * @return A success message if the asset is deleted, or a detailed error message.
     */
    @DeleteMapping("/{assetId}") // Using DELETE for deletion
    public String deleteAsset(@PathVariable String assetId) {
        try {
            Network network = gateway.getNetwork(CHANNEL_NAME);
            Contract contract = network.getContract(CHAINCODE_NAME);
            contract.submitTransaction("DeleteAsset", assetId); // Submit the DeleteAsset transaction
            return "Asset " + assetId + " deleted successfully!";
        } catch (GatewayException | CommitException e) {
            System.err.println("Error deleting asset " + assetId + ": " + e.getMessage());
            e.printStackTrace();
            // Detailed error handling for various Fabric transaction exceptions.
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

    /**
     * This method is called by Spring before the bean is destroyed (e.g., when the
     * application shuts down). It ensures that the gRPC channel to the Fabric peer
     * is gracefully shut down to release resources.
     */
    @PreDestroy
    public void shutdown() {
        if (channel != null && !channel.isShutdown()) {
            try {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                System.out.println("gRPC channel shut down successfully.");
            } catch (InterruptedException e) {
                System.err.println("Failed to shut down gRPC channel: " + e.getMessage());
                Thread.currentThread().interrupt(); // Restore the interrupted status
            }
        }
    }

    /**
     * A simple Data Transfer Object (DTO) class to represent the structure of
     * an incoming JSON request body for creating or updating an asset.
     * Spring automatically maps the JSON fields to these public fields.
     */
    static class AssetCreationRequest {
        public String assetID;
        public String color;
        public int size;
        public String owner;
        public int appraisedValue;
    }
}
