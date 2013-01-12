/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.build.gradle.internal.test.report;


import org.gradle.api.Action;
import org.w3c.dom.Element;

/**
 * Custom OverviewPageRenderer based on Gradle's OverviewPageRenderer
 */
class OverviewPageRenderer extends PageRenderer<AllTestResults> {

    public OverviewPageRenderer(ReportType reportType) {
        super(reportType);
    }

    @Override protected void registerTabs() {
        addFailuresTab();
        if (!getResults().getPackages().isEmpty()) {
            addTab("Packages", new Action<Element>() {
                @Override
                public void execute(Element element) {
                    renderPackages(element);
                }
            });
        }
        addTab("Classes", new Action<Element>() {
            @Override
            public void execute(Element element) {
                renderClasses(element);
            }
        });
    }

    @Override protected void renderBreadcrumbs(Element element) {
    }

    private void renderPackages(Element parent) {
        Element table = append(parent, "table");
        Element thead = append(table, "thead");
        Element tr = append(thead, "tr");
        if (reportType == ReportType.MULTI_PROJECT) {
            appendWithText(tr, "th", "Project");
            appendWithText(tr, "th", "Flavor");
        } else if (reportType == ReportType.MULTI_FLAVOR) {
            appendWithText(tr, "th", "Flavor");
        }
        appendWithText(tr, "th", "Package");
        appendWithText(tr, "th", "Tests");
        appendWithText(tr, "th", "Failures");
        appendWithText(tr, "th", "Duration");
        appendWithText(tr, "th", "Success rate");
        for (PackageTestResults testPackage : getResults().getPackages()) {
            tr = append(table, "tr");
            Element td;

            if (reportType == ReportType.MULTI_PROJECT) {
                td = appendWithText(tr, "td", testPackage.getProject());
                td.setAttribute("class", testPackage.getStatusClass());
                td = appendWithText(tr, "td", testPackage.getFlavor());
                td.setAttribute("class", testPackage.getStatusClass());
            } else if (reportType == ReportType.MULTI_FLAVOR) {
                td = appendWithText(tr, "td", testPackage.getFlavor());
                td.setAttribute("class", testPackage.getStatusClass());
            }

            td = append(tr, "td");
            td.setAttribute("class", testPackage.getStatusClass());
            appendLink(td,
                    String.format("%s.html", testPackage.getFilename(reportType)),
                    testPackage.getName());
            appendWithText(tr, "td", testPackage.getTestCount());
            appendWithText(tr, "td", testPackage.getFailureCount());
            appendWithText(tr, "td", testPackage.getFormattedDuration());
            td = appendWithText(tr, "td", testPackage.getFormattedSuccessRate());
            td.setAttribute("class", testPackage.getStatusClass());
        }
    }

    private void renderClasses(Element parent) {
        Element table = append(parent, "table");
        Element thead = append(table, "thead");
        Element tr = append(thead, "tr");
        if (reportType == ReportType.MULTI_PROJECT) {
            appendWithText(tr, "th", "Project");
            appendWithText(tr, "th", "Flavor");
        } else if (reportType == ReportType.MULTI_FLAVOR) {
            appendWithText(tr, "th", "Flavor");
        }
        appendWithText(tr, "th", "Class");
        appendWithText(tr, "th", "Tests");
        appendWithText(tr, "th", "Failures");
        appendWithText(tr, "th", "Duration");
        appendWithText(tr, "th", "Success rate");
        for (PackageTestResults testPackage : getResults().getPackages()) {
            for (ClassTestResults testClass : testPackage.getClasses()) {
                tr = append(table, "tr");
                Element td;

                if (reportType == ReportType.MULTI_PROJECT) {
                    td = appendWithText(tr, "td", testClass.getProject());
                    td.setAttribute("class", testClass.getStatusClass());
                    td = appendWithText(tr, "td", testClass.getFlavor());
                    td.setAttribute("class", testClass.getStatusClass());
                } else if (reportType == ReportType.MULTI_FLAVOR) {
                    td = appendWithText(tr, "td", testClass.getFlavor());
                    td.setAttribute("class", testClass.getStatusClass());
                }

                td = append(tr, "td");
                td.setAttribute("class", testClass.getStatusClass());
                appendLink(td,
                        String.format("%s.html", testClass.getFilename(reportType)),
                        testClass.getName());
                appendWithText(tr, "td", testClass.getTestCount());
                appendWithText(tr, "td", testClass.getFailureCount());
                appendWithText(tr, "td", testClass.getFormattedDuration());
                td = appendWithText(tr, "td", testClass.getFormattedSuccessRate());
                td.setAttribute("class", testClass.getStatusClass());
            }
        }
    }
}