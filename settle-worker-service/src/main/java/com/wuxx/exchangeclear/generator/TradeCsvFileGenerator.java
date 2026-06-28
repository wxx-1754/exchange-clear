package com.wuxx.exchangeclear.generator;

import com.wuxx.exchangeclear.common.Md5Util;
import com.wuxx.exchangeclear.config.ExchangeClearFileProperties;
import com.wuxx.exchangeclear.enums.FileTypeEnum;
import com.wuxx.exchangeclear.trade.entity.TradeRecord;
import com.wuxx.exchangeclear.trade.mapper.TradeRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TradeCsvFileGenerator implements FileGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CSV_HEADER = "tradeNo,memberId,productId,contractId,direction,price,volume,amount,tradeTime";

    private final TradeRecordMapper tradeRecordMapper;

    private final ExchangeClearFileProperties fileProperties;

    @Override
    public boolean support(String fileType) {
        return FileTypeEnum.TRADE.getCode().equalsIgnoreCase(fileType);
    }

    @Override
    public FileGenerateResult generate(FileGenerateContext context) {
        String dateText = context.getSettleDate().format(DATE_FORMATTER);
        String fileName = "trade_" + context.getMemberId() + "_" + dateText + ".csv";
        Path directory = Path.of(
                fileProperties.getLocalRootPath(),
                dateText,
                context.getMemberId(),
                context.getFileType()
        );
        Path tempFile = directory.resolve(fileName + ".tmp");
        Path targetFile = directory.resolve(fileName);

        try {
            Files.createDirectories(directory);
            long rowCount = writeCsv(context, tempFile);
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            long fileSize = Files.size(targetFile);
            String fileMd5 = Md5Util.calculate(targetFile);
            log.info("[taskNo={}] local file generated, path={}, rows={}", context.getTaskNo(), targetFile, rowCount);
            return new FileGenerateResult(fileName, targetFile, fileSize, fileMd5);
        } catch (Exception e) {
            deleteQuietly(tempFile);
            throw new IllegalStateException("生成成交CSV文件失败", e);
        }
    }

    private long writeCsv(FileGenerateContext context, Path tempFile) throws IOException {
        long lastId = 0L;
        long total = 0L;
        int pageSize = fileProperties.getPageSize();

        try (BufferedWriter writer = Files.newBufferedWriter(tempFile, StandardCharsets.UTF_8)) {
            writer.write(CSV_HEADER);
            writer.newLine();

            while (true) {
                List<TradeRecord> records = tradeRecordMapper.selectNextPage(
                        context.getSettleDate(),
                        context.getMemberId(),
                        lastId,
                        pageSize
                );
                if (records.isEmpty()) {
                    break;
                }
                for (TradeRecord record : records) {
                    writer.write(toCsvLine(record));
                    writer.newLine();
                    lastId = record.getId();
                    total++;
                }
                writer.flush();
            }
        }
        return total;
    }

    private String toCsvLine(TradeRecord record) {
        return record.getTradeNo()
                + "," + record.getMemberId()
                + "," + record.getProductId()
                + "," + record.getContractId()
                + "," + record.getDirection()
                + "," + toPlainString(record.getPrice())
                + "," + record.getVolume()
                + "," + toPlainString(record.getAmount())
                + "," + record.getTradeTime().format(DATE_TIME_FORMATTER);
    }

    private String toPlainString(BigDecimal value) {
        return value == null ? "" : value.toPlainString();
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("删除临时文件失败, path={}", path, e);
        }
    }
}
