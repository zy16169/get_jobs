package boss;

import lombok.SneakyThrows;
import org.json.JSONObject;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.Job;
import utils.JobUtils;
import utils.SeleniumUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static utils.Bot.sendMessageByTime;
import static utils.Constant.CHROME_DRIVER;
import static utils.Constant.WAIT;
import static utils.JobUtils.formatDuration;

/**
 * @author loks666
 * Boss直聘自动投递
 */
public class Boss {
    static final int noJobMaxPages = 5; // 无岗位最大页数
    private static final Logger log = LoggerFactory.getLogger(Boss.class);
    static Integer page = 1;
    static String homeUrl = "https://www.zhipin.com";
    static String baseUrl = "https://www.zhipin.com/web/geek/job?";
    static Set<String> blackCompanies;
    static Set<String> blackRecruiters;
    static Set<String> blackJobs;
    static List<Job> returnList = new ArrayList<>();
    static String dataPath = "./src/main/java/boss/data.json";
    static String cookiePath = "./src/main/java/boss/cookie.json";
    static int noJobPages;
    static int lastSize;
    static Date startDate;
    static BossConfig config = BossConfig.init();

    public static void main(String[] args) {
        loadData(dataPath);
        SeleniumUtil.initDriver();
        startDate = new Date();
        login();
        config.getCityCode().forEach(Boss::postJobByCity);
        log.info(returnList.isEmpty() ? "未发起新的聊天..." : "新发起聊天公司如下:\n{}", returnList.stream().map(Object::toString).collect(Collectors.joining("\n")));
        printResult();
    }

    private static void printResult() {
        String message = String.format("\nBoss投递完成，共发起%d个聊天，用时%s", returnList.size(), formatDuration(startDate, new Date()));
        log.info(message);
        sendMessageByTime(message);
        saveData(dataPath);
        CHROME_DRIVER.close();
        CHROME_DRIVER.quit();
    }

    private static void postJobByCity(String cityCode) {
        String searchUrl = getSearchUrl(cityCode);
        endSubmission:
        for (String keyword : config.getKeywords()) {
            page = 1;
            noJobPages = 0;
            lastSize = -1;
            while (true) {
                log.info("投递【{}】关键词第【{}】页", keyword, page);
                String url = searchUrl + "&page=" + page;
                int startSize = returnList.size();
                Integer resultSize = resumeSubmission(url, keyword);
                if (resultSize == -1) {
                    log.info("今日沟通人数已达上限，请明天再试");
                    break endSubmission;
                }
                if (resultSize == -2) {
                    log.info("出现异常访问，请手动过验证后再继续投递...");
                    break endSubmission;
                }
                if (resultSize == startSize) {
                    noJobPages++;
                    if (noJobPages >= noJobMaxPages) {
                        log.info("【{}】关键词已经连续【{}】页无岗位，结束该关键词的投递...", keyword, noJobPages);
                        break;
                    } else {
                        log.info("【{}】关键词第【{}】页无岗位,目前已连续【{}】页无新岗位...", keyword, page, noJobPages);
                    }
                } else {
                    lastSize = resultSize;
                    noJobPages = 0;
                }
                page++;
            }
        }
    }

    private static String getSearchUrl(String cityCode) {
        return baseUrl +
                JobUtils.appendParam("city", cityCode) +
                JobUtils.appendParam("jobType", config.getJobType()) +
                JobUtils.appendParam("salary", config.getSalary()) +
                JobUtils.appendListParam("experience", config.getExperience()) +
                JobUtils.appendListParam("degree", config.getDegree()) +
                JobUtils.appendListParam("scale", config.getScale()) +
                JobUtils.appendListParam("stage", config.getStage()) +
                "&areaBusiness=440309,440311,440306,440307,440304,440305";
    }

    private static void saveData(String path) {
        try {
            updateListData();
            Map<String, Set<String>> data = new HashMap<>();
            data.put("blackCompanies", blackCompanies);
            data.put("blackRecruiters", blackRecruiters);
            data.put("blackJobs", blackJobs);
            String json = customJsonFormat(data);
            Files.write(Paths.get(path), json.getBytes());
        } catch (IOException e) {
            log.error("保存【{}】数据失败！", path);
        }
    }

    private static void updateListData() {
        CHROME_DRIVER.get("https://www.zhipin.com/web/geek/chat");
        SeleniumUtil.getWait(3);

        JavascriptExecutor js = CHROME_DRIVER;
        boolean shouldBreak = false;
        while (!shouldBreak) {
            try {
                WebElement bottom = CHROME_DRIVER.findElement(By.xpath("//div[@class='finished']"));
                if ("没有更多了".equals(bottom.getText())) {
                    shouldBreak = true;
                }
            } catch (Exception ignore) {
            }
            List<WebElement> items = CHROME_DRIVER.findElements(By.xpath("//li[@role='listitem']"));
            for (int i = 0; i < items.size(); i++) {
                try {
                    WebElement companyElement = CHROME_DRIVER.findElements(By.xpath("//span[@class='name-box']//span[2]")).get(i);
                    String companyName = companyElement.getText();
                    WebElement messageElement = CHROME_DRIVER.findElements(By.xpath("//span[@class='last-msg-text']")).get(i);
                    String message = messageElement.getText();
                    boolean match = message.contains("不") || message.contains("感谢") || message.contains("但") || message.contains("遗憾") || message.contains("需要本") || message.contains("对不");
                    boolean nomatch = message.contains("不是") || message.contains("不生");
                    if (match && !nomatch) {
                        log.info("黑名单公司：【{}】，信息：【{}】", companyName, message);
                        if (blackCompanies.stream().anyMatch(companyName::contains)) {
                            continue;
                        }
                        companyName = companyName.replaceAll("\\.{3}", "");
                        if (companyName.matches(".*(\\p{IsHan}{2,}|[a-zA-Z]{4,}).*")) {
                            blackCompanies.add(companyName);
                        }
                    }
                } catch (Exception e) {
                    log.error("寻找黑名单公司异常...");
                }
            }
            WebElement element = null;
            try {
                WAIT.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[contains(text(), '滚动加载更多')]")));
                element = CHROME_DRIVER.findElement(By.xpath("//div[contains(text(), '滚动加载更多')]"));
            } catch (Exception e) {
                log.info("没找到滚动条...");
            }

            if (element != null) {
                try {
                    js.executeScript("arguments[0].scrollIntoView();", element);
                } catch (Exception e) {
                    log.error("滚动到元素出错", e);
                }
            } else {
                try {
                    js.executeScript("window.scrollTo(0, document.body.scrollHeight);");
                } catch (Exception e) {
                    log.error("滚动到页面底部出错", e);
                }
            }
        }
        log.info("黑名单公司数量：{}", blackCompanies.size());
    }


    private static String customJsonFormat(Map<String, Set<String>> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        for (Map.Entry<String, Set<String>> entry : data.entrySet()) {
            sb.append("    \"").append(entry.getKey()).append("\": [\n");
            sb.append(entry.getValue().stream()
                    .map(s -> "        \"" + s + "\"")
                    .collect(Collectors.joining(",\n")));

            sb.append("\n    ],\n");
        }
        sb.delete(sb.length() - 2, sb.length());
        sb.append("\n}");
        return sb.toString();
    }

    private static void loadData(String path) {
        try {
            String json = new String(Files.readAllBytes(Paths.get(path)));
            parseJson(json);
        } catch (IOException e) {
            log.error("读取【{}】数据失败！", path);
        }
    }

    private static void parseJson(String json) {
        JSONObject jsonObject = new JSONObject(json);
        blackCompanies = jsonObject.getJSONArray("blackCompanies").toList().stream().map(Object::toString).collect(Collectors.toSet());
        blackRecruiters = jsonObject.getJSONArray("blackRecruiters").toList().stream().map(Object::toString).collect(Collectors.toSet());
        blackJobs = jsonObject.getJSONArray("blackJobs").toList().stream().map(Object::toString).collect(Collectors.toSet());
    }

    @SneakyThrows
    private static Integer resumeSubmission(String url, String keyword) {
        CHROME_DRIVER.get(url + "&query=" + keyword);
        WAIT.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='job-title clearfix']")));
        List<WebElement> jobCards = CHROME_DRIVER.findElements(By.cssSelector("li.job-card-wrapper"));
        List<Job> jobs = new ArrayList<>();
        for (WebElement jobCard : jobCards) {
            WebElement infoPublic = jobCard.findElement(By.cssSelector("div.info-public"));
            String recruiterText = infoPublic.getText();
            String recruiterName = infoPublic.findElement(By.cssSelector("em")).getText();
            if (blackRecruiters.stream().anyMatch(recruiterName::contains)) {
                // 排除黑名单招聘人员
                continue;
            }
            String jobName = jobCard.findElement(By.cssSelector("div.job-title span.job-name")).getText();
            if (blackJobs.stream().anyMatch(jobName::contains) || !isTargetJob(keyword, jobName)) {
                // 排除黑名单岗位
                continue;
            }
            String companyName = jobCard.findElement(By.cssSelector("div.company-info h3.company-name")).getText();
            if (blackCompanies.stream().anyMatch(companyName::contains)) {
                // 排除黑名单公司
                continue;
            }
            Job job = new Job();
            job.setRecruiter(recruiterText.replace(recruiterName, "") + ":" + recruiterName);
            job.setHref(jobCard.findElement(By.cssSelector("a")).getAttribute("href"));
            job.setJobName(jobName);
            job.setJobArea(jobCard.findElement(By.cssSelector("div.job-title span.job-area")).getText());
            job.setSalary(jobCard.findElement(By.cssSelector("div.job-info span.salary")).getText());
            List<WebElement> tagElements = jobCard.findElements(By.cssSelector("div.job-info ul.tag-list li"));
            StringBuilder tag = new StringBuilder();
            for (WebElement tagElement : tagElements) {
                tag.append(tagElement.getText()).append("·");
            }
            job.setCompanyTag(tag.substring(0, tag.length() - 1)); // 删除最后一个 "·"
            jobs.add(job);
        }
        for (Job job : jobs) {
            // 打开新的标签页并打开链接
            JavascriptExecutor jse = CHROME_DRIVER;
            jse.executeScript("window.open(arguments[0], '_blank')", job.getHref());

            // 切换到新的标签页
            ArrayList<String> tabs = new ArrayList<>(CHROME_DRIVER.getWindowHandles());
            CHROME_DRIVER.switchTo().window(tabs.get(tabs.size() - 1));
            try {
                WAIT.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='btn btn-startchat']")));
            } catch (Exception e) {
                WebElement element = SeleniumUtil.findElement(By.xpath("//div[@class='error-content']"));
                if (element != null && element.getText().contains("异常访问")) {
                    return -2;
                }
            }
            SeleniumUtil.sleep(1);
            WebElement btn = CHROME_DRIVER.findElement(By.cssSelector("[class*='btn btn-startchat']"));
            if ("立即沟通".equals(btn.getText())) {
                btn.click();
                if (isLimit()) {
                    SeleniumUtil.sleep(1);
                    return -1;
                }
                try {
                    SeleniumUtil.sleep(1);
                    try {
                        CHROME_DRIVER.findElement(By.xpath("//textarea[@class='input-area']"));
                        WebElement close = CHROME_DRIVER.findElement(By.xpath("//i[@class='icon-close']"));
                        close.click();
                        btn.click();
                    } catch (Exception ignore) {
                    }
                    WebElement input = WAIT.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//div[@id='chat-input']")));
                    input.click();
                    SeleniumUtil.sleep(1);
                    try {
                        // 是否出现不匹配的对话框
                        WebElement element = CHROME_DRIVER.findElement(By.xpath("//div[@class='dialog-container']"));
                        if ("不匹配".equals(element.getText())) {
                            CHROME_DRIVER.close();
                            CHROME_DRIVER.switchTo().window(tabs.get(0));
                            continue;
                        }
                    } catch (Exception e) {
                        log.debug("岗位匹配，下一步发送消息...");
                    }
                    input.sendKeys(config.getSayHi());
                    WebElement send = WAIT.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//button[@type='send']")));
                    send.click();

                    WebElement recruiterNameElement = CHROME_DRIVER.findElement(By.xpath("//p[@class='base-info fl']/span[@class='name']"));
                    WebElement recruiterTitleElement = CHROME_DRIVER.findElement(By.xpath("//p[@class='base-info fl']/span[@class='base-title']"));
                    String recruiter = recruiterNameElement.getText() + " " + recruiterTitleElement.getText();

                    WebElement companyElement;
                    try {
                        companyElement = CHROME_DRIVER.findElement(By.xpath("//p[@class='base-info fl']/span[not(@class)]"));
                    } catch (Exception e) {
                        WAIT.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//p[@class='base-info fl']/span[not(@class)]")));
                        companyElement = CHROME_DRIVER.findElement(By.xpath("//p[@class='base-info fl']/span[not(@class)]"));

                    }
                    String company = null;
                    if (companyElement != null) {
                        company = companyElement.getText();
                        job.setCompanyName(company);
                    }

                    WebElement positionNameElement = CHROME_DRIVER.findElement(By.xpath("//a[@class='position-content']/span[@class='position-name']"));
                    WebElement salaryElement = CHROME_DRIVER.findElement(By.xpath("//a[@class='position-content']/span[@class='salary']"));
                    WebElement cityElement = CHROME_DRIVER.findElement(By.xpath("//a[@class='position-content']/span[@class='city']"));
                    String position = positionNameElement.getText() + " " + salaryElement.getText() + " " + cityElement.getText();
                    log.info("投递【{}】公司，【{}】职位，招聘官:【{}】", company == null ? "未知公司: " + job.getHref() : company, position, recruiter);
                    returnList.add(job);
                    noJobPages = 0;
                } catch (Exception e) {
                    log.error("发送消息失败:{}", e.getMessage(), e);
                }
            }
            SeleniumUtil.sleep(1);
            CHROME_DRIVER.close();
            CHROME_DRIVER.switchTo().window(tabs.get(0));
        }
        return returnList.size();
    }

    private static boolean isTargetJob(String keyword, String jobName) {
        boolean keywordIsAI = false;
        for (String target : new String[]{"大模型", "AI"}) {
            if (keyword.contains(target)) {
                keywordIsAI = true;
                break;
            }
        }

        boolean jobIsDesign = false;
        for (String designOrVision : new String[]{"设计", "视觉", "产品", "运营"}) {
            if (jobName.contains(designOrVision)) {
                jobIsDesign = true;
                break;
            }
        }

        boolean jobIsAI = false;
        for (String target : new String[]{"AI", "人工智能", "大模型", "生成"}) {
            if (jobName.contains(target)) {
                jobIsAI = true;
                break;
            }
        }

        if (keywordIsAI) {
            if (jobIsDesign) {
                return false;
            } else if (!jobIsAI) {
                return true;
            }
        }
        return true;
    }


    private static boolean isLimit() {
        try {
            SeleniumUtil.sleep(1);
            String text = CHROME_DRIVER.findElement(By.className("dialog-con")).getText();
            return text.contains("已达上限");
        } catch (Exception e) {
            return false;
        }
    }

    @SneakyThrows
    private static void login() {
        log.info("打开Boss直聘网站中...");
        CHROME_DRIVER.get(homeUrl);
        if (SeleniumUtil.isCookieValid(cookiePath)) {
            SeleniumUtil.loadCookie(cookiePath);
            CHROME_DRIVER.navigate().refresh();
            SeleniumUtil.sleep(2);
        }
        if (isLoginRequired()) {
            log.error("cookie失效，尝试扫码登录...");
            scanLogin();
        }
    }


    private static boolean isLoginRequired() {
        try {
            String text = CHROME_DRIVER.findElement(By.className("btns")).getText();
            return text != null && text.contains("登录");
        } catch (Exception e) {
            log.info("cookie有效，已登录...");
            return false;
        }
    }

    @SneakyThrows
    private static void scanLogin() {
        CHROME_DRIVER.get(homeUrl + "/web/user/?ka=header-login");
        log.info("等待登陆..");
        WAIT.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//a[@ka='header-home-logo']")));
        boolean login = false;
        while (!login) {
            try {
                WAIT.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//*[@id=\"header\"]/div[1]/div[1]/a")));
                WAIT.until(ExpectedConditions.presenceOfElementLocated(By.xpath("//*[@id=\"wrap\"]/div[2]/div[1]/div/div[1]/a[2]")));
                login = true;
                log.info("登录成功！保存cookie...");
            } catch (Exception e) {
                log.error("登陆失败，两秒后重试...");
            } finally {
                SeleniumUtil.sleep(2);
            }
        }
        SeleniumUtil.saveCookie(cookiePath);
    }


}

