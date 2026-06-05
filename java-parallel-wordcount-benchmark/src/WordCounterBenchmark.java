import static org.jocl.CL.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;


public class WordCounterBenchmark {
    private static final int REPETICOES = 3;
    private static final String PALAVRA_PADRAO = "the";
    private static final Path DATA_DIR = Paths.get("data");
    private static final Path RESULTS_DIR = Paths.get("results");
    private static final DecimalFormat DF = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));
    private static final DecimalFormat CSV_DF = new DecimalFormat("0.0000", DecimalFormatSymbols.getInstance(Locale.US));

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        Files.createDirectories(RESULTS_DIR);

        String palavra = args.length >= 1 ? args[0] : PALAVRA_PADRAO;
        List<Path> arquivos = listarTxts(DATA_DIR);

        if (arquivos.isEmpty()) {
            System.err.println("Nenhum arquivo .txt encontrado na pasta data/.");
            return;
        }

        int nucleosDisponiveis = Runtime.getRuntime().availableProcessors();
        int[] configuracoesThreads = gerarConfiguracoesThreads(nucleosDisponiveis);

        Path csv = RESULTS_DIR.resolve("resultados.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("arquivo,tamanho_bytes,palavra,metodo,threads,repeticao,ocorrencias,tempo_ms\n");

            for (Path arquivo : arquivos) {
                byte[] texto = Files.readAllBytes(arquivo);
                byte[] alvo = normalizarPalavra(palavra);

                System.out.println("\nArquivo: " + arquivo.getFileName() + " | tamanho: " + texto.length + " bytes | palavra: " + palavra);

                for (int repeticao = 1; repeticao <= REPETICOES; repeticao++) {
                    Resultado serial = medir(() -> SerialCPU(texto, alvo));
                    imprimir("SerialCPU", serial, 1);
                    escreverLinha(writer, arquivo, texto.length, palavra, "SerialCPU", 1, repeticao, serial);
                }

                for (int threads : configuracoesThreads) {
                    for (int repeticao = 1; repeticao <= REPETICOES; repeticao++) {
                        final int t = threads;
                        Resultado paraleloCpu = medir(() -> ParallelCPU(texto, alvo, t));
                        imprimir("ParallelCPU", paraleloCpu, t);
                        escreverLinha(writer, arquivo, texto.length, palavra, "ParallelCPU", t, repeticao, paraleloCpu);
                    }
                }

                for (int repeticao = 1; repeticao <= REPETICOES; repeticao++) {
                    try {
                        Resultado paraleloGpu = medir(() -> ParallelGPU(texto, alvo));
                        imprimir("ParallelGPU", paraleloGpu, 0);
                        escreverLinha(writer, arquivo, texto.length, palavra, "ParallelGPU", 0, repeticao, paraleloGpu);
                    } catch (Throwable ex) {
                        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                        System.out.println("ParallelGPU: não executado nesta máquina (" + msg + ")");
                        writer.write(String.join(",",
                                arquivo.getFileName().toString(),
                                String.valueOf(texto.length),
                                palavra,
                                "ParallelGPU",
                                "0",
                                String.valueOf(repeticao),
                                "-1",
                                "-1"));
                        writer.write("\n");
                    }
                }
            }
        }

        System.out.println("\nCSV gerado em: " + csv.toAbsolutePath());
        System.out.println("Abra o notebook analise_graficos_jupyter.ipynb para gerar os gráficos pela interface Jupyter.");
    }


    // Sobrecargas que recebem diretamente arquivo .txt e palavra, conforme o enunciado.
    public static int SerialCPU(Path arquivoTxt, String palavra) throws IOException {
        return SerialCPU(Files.readAllBytes(arquivoTxt), normalizarPalavra(palavra));
    }

    public static int ParallelCPU(Path arquivoTxt, String palavra, int numeroThreads) throws Exception {
        return ParallelCPU(Files.readAllBytes(arquivoTxt), normalizarPalavra(palavra), numeroThreads);
    }

    public static int ParallelGPU(Path arquivoTxt, String palavra) throws IOException {
        return ParallelGPU(Files.readAllBytes(arquivoTxt), normalizarPalavra(palavra));
    }

    public static int SerialCPU(byte[] texto, byte[] alvo) {
        int total = 0;
        for (int i = 0; i < texto.length; i++) {
            if (ehOcorrencia(texto, alvo, i)) {
                total++;
            }
        }
        return total;
    }

    public static int ParallelCPU(byte[] texto, byte[] alvo, int numeroThreads) throws Exception {
        if (numeroThreads <= 0) numeroThreads = 1;
        ExecutorService executor = Executors.newFixedThreadPool(numeroThreads);
        List<Future<Integer>> resultados = new ArrayList<>();
        int tamanho = texto.length;
        int bloco = (int) Math.ceil(tamanho / (double) numeroThreads);

        for (int thread = 0; thread < numeroThreads; thread++) {
            final int inicio = thread * bloco;
            final int fim = Math.min(tamanho, inicio + bloco);
            if (inicio >= fim) break;

            resultados.add(executor.submit(new Callable<Integer>() {
                @Override
                public Integer call() {
                    int parcial = 0;
                    for (int i = inicio; i < fim; i++) {
                        if (ehOcorrencia(texto, alvo, i)) {
                            parcial++;
                        }
                    }
                    return parcial;
                }
            }));
        }

        int total = 0;
        for (Future<Integer> f : resultados) {
            total += f.get();
        }
        executor.shutdown();
        return total;
    }

    public static int ParallelGPU(byte[] texto, byte[] alvo) {
        if (alvo.length == 0 || texto.length == 0) return 0;

        CL.setExceptionsEnabled(true);

        int[] numPlatformsArray = new int[1];
        clGetPlatformIDs(0, null, numPlatformsArray);
        int numPlatforms = numPlatformsArray[0];
        if (numPlatforms == 0) {
            throw new RuntimeException("OpenCL não encontrou plataformas");
        }

        cl_platform_id[] platforms = new cl_platform_id[numPlatforms];
        clGetPlatformIDs(platforms.length, platforms, null);

        cl_device_id device = null;
        cl_platform_id platformEscolhida = null;

        for (cl_platform_id platform : platforms) {
            int[] numDevicesArray = new int[1];
            try {
                clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, null, numDevicesArray);
                if (numDevicesArray[0] > 0) {
                    cl_device_id[] devices = new cl_device_id[numDevicesArray[0]];
                    clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices.length, devices, null);
                    device = devices[0];
                    platformEscolhida = platform;
                    break;
                }
            } catch (Exception ignored) {
                // Tenta a próxima plataforma
            }
        }

        if (device == null) {
            throw new RuntimeException("nenhum dispositivo GPU OpenCL encontrado");
        }

        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, platformEscolhida);
        cl_context context = clCreateContext(contextProperties, 1, new cl_device_id[]{device}, null, null, null);
        cl_command_queue queue = clCreateCommandQueue(context, device, 0, null);

        String source = kernelSource();
        cl_program program = clCreateProgramWithSource(context, 1, new String[]{source}, null, null);
        clBuildProgram(program, 0, null, null, null, null);
        cl_kernel kernel = clCreateKernel(program, "countWords", null);

        int n = texto.length;
        int[] flags = new int[n];

        cl_mem memTexto = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_char * texto.length, Pointer.to(texto), null);
        cl_mem memAlvo = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                (long) Sizeof.cl_char * alvo.length, Pointer.to(alvo), null);
        cl_mem memFlags = clCreateBuffer(context, CL_MEM_WRITE_ONLY,
                (long) Sizeof.cl_int * flags.length, null, null);

        clSetKernelArg(kernel, 0, Sizeof.cl_mem, Pointer.to(memTexto));
        clSetKernelArg(kernel, 1, Sizeof.cl_int, Pointer.to(new int[]{texto.length}));
        clSetKernelArg(kernel, 2, Sizeof.cl_mem, Pointer.to(memAlvo));
        clSetKernelArg(kernel, 3, Sizeof.cl_int, Pointer.to(new int[]{alvo.length}));
        clSetKernelArg(kernel, 4, Sizeof.cl_mem, Pointer.to(memFlags));

        long[] globalWorkSize = new long[]{n};
        clEnqueueNDRangeKernel(queue, kernel, 1, null, globalWorkSize, null, 0, null, null);
        clEnqueueReadBuffer(queue, memFlags, CL_TRUE, 0,
                (long) Sizeof.cl_int * flags.length, Pointer.to(flags), 0, null, null);

        int total = 0;
        for (int flag : flags) total += flag;

        clReleaseMemObject(memTexto);
        clReleaseMemObject(memAlvo);
        clReleaseMemObject(memFlags);
        clReleaseKernel(kernel);
        clReleaseProgram(program);
        clReleaseCommandQueue(queue);
        clReleaseContext(context);

        return total;
    }

    private static String kernelSource() {
        return "char toLowerAscii(char c) {\n" +
                "  if (c >= 'A' && c <= 'Z') return c + 32;\n" +
                "  return c;\n" +
                "}\n" +
                "int isAlphaNum(char c) {\n" +
                "  c = toLowerAscii(c);\n" +
                "  return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');\n" +
                "}\n" +
                "__kernel void countWords(__global const char *texto, const int n, " +
                "__global const char *alvo, const int m, __global int *flags) {\n" +
                "  int i = get_global_id(0);\n" +
                "  flags[i] = 0;\n" +
                "  if (i + m > n) return;\n" +
                "  if (i > 0 && isAlphaNum(texto[i-1])) return;\n" +
                "  if (i + m < n && isAlphaNum(texto[i+m])) return;\n" +
                "  for (int j = 0; j < m; j++) {\n" +
                "    char c = toLowerAscii(texto[i+j]);\n" +
                "    if (c != alvo[j]) return;\n" +
                "  }\n" +
                "  flags[i] = 1;\n" +
                "}\n";
    }

    private static boolean ehOcorrencia(byte[] texto, byte[] alvo, int pos) {
        if (alvo.length == 0 || pos + alvo.length > texto.length) return false;
        if (pos > 0 && isAlphaNum(texto[pos - 1])) return false;
        if (pos + alvo.length < texto.length && isAlphaNum(texto[pos + alvo.length])) return false;

        for (int j = 0; j < alvo.length; j++) {
            if (toLowerAscii(texto[pos + j]) != alvo[j]) return false;
        }
        return true;
    }

    private static byte toLowerAscii(byte b) {
        if (b >= 'A' && b <= 'Z') return (byte) (b + 32);
        return b;
    }

    private static boolean isAlphaNum(byte b) {
        b = toLowerAscii(b);
        return (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9');
    }

    private static byte[] normalizarPalavra(String palavra) {
        return palavra.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
    }

    private static Resultado medir(OperacaoContagem op) throws Exception {
        long inicio = System.nanoTime();
        int ocorrencias = op.executar();
        long fim = System.nanoTime();
        return new Resultado(ocorrencias, (fim - inicio) / 1_000_000.0);
    }

    private static void imprimir(String metodo, Resultado r, int threads) {
        String detalheThreads = threads > 0 ? " | threads=" + threads : "";
        System.out.println(metodo + detalheThreads + ": " + r.ocorrencias + " ocorrências em " + DF.format(r.tempoMs) + " ms");
    }

    private static void escreverLinha(BufferedWriter writer, Path arquivo, int tamanho, String palavra,
                                      String metodo, int threads, int repeticao, Resultado resultado) throws IOException {
        writer.write(String.join(",",
                arquivo.getFileName().toString(),
                String.valueOf(tamanho),
                palavra,
                metodo,
                String.valueOf(threads),
                String.valueOf(repeticao),
                String.valueOf(resultado.ocorrencias),
                CSV_DF.format(resultado.tempoMs)));
        writer.write("\n");
    }

    private static List<Path> listarTxts(Path dir) throws IOException {
        if (!Files.exists(dir)) return List.of();
        List<Path> arquivos = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(arquivos::add);
        }
        return arquivos;
    }

    private static int[] gerarConfiguracoesThreads(int nucleos) {
        List<Integer> valores = new ArrayList<>();
        valores.add(1);
        if (nucleos >= 2) valores.add(2);
        if (nucleos >= 4) valores.add(4);
        if (nucleos > 4) valores.add(nucleos);
        return valores.stream().distinct().mapToInt(Integer::intValue).toArray();
    }

    private interface OperacaoContagem {
        int executar() throws Exception;
    }

    private static class Resultado {
        int ocorrencias;
        double tempoMs;
        Resultado(int ocorrencias, double tempoMs) {
            this.ocorrencias = ocorrencias;
            this.tempoMs = tempoMs;
        }
    }
}
